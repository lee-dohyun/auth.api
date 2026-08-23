package com.dh.auth.service.sms;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import net.nurigo.sdk.NurigoApp;
import net.nurigo.sdk.message.model.Message;
import net.nurigo.sdk.message.request.MessageListRequest;
import net.nurigo.sdk.message.request.SingleMessageSendingRequest;
import net.nurigo.sdk.message.response.MessageListResponse;
import net.nurigo.sdk.message.response.SingleMessageSentResponse;
import net.nurigo.sdk.message.service.DefaultMessageService;

/**
 * Solapi 벤더 구현. {@code sms.provider} 가 {@code solapi} 이거나 비어 있을 때만 등록된다.
 * 자기 벤더 일만 한다 — mock 분기는 {@link MockSmsProvider} 로 옮겼다.
 */
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "solapi", matchIfMissing = true)
public class SolapiSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(SolapiSmsProvider.class);

    /** Solapi SDK {@code MessageStatusType.COMPLETE} — 통신사까지 실제로 전달된 최종 성공 코드. */
    private static final String STATUS_COMPLETE = "4000";
    private static final String METRIC_SEND_FAILED = "sms.send.failed";

    /**
     * auth.api#34 — sendOne()의 2000은 "접수"일 뿐 "전달"이 아니다. 통신사 결과 리포트는
     * 비동기로 나중에 온다(실측: 약 3초). 정확한 시점을 기다리는 대신, 한 번 지연 조회로
     * 최종 상태를 확인한다 — 이 시점에도 아직 SENDING(3000)이면 놓칠 수 있는 걸 감수한
     * 최소 조치다(캐논 §2 "장기과제로 기록만"과 같은 결의 판단, 이슈의 "가장 싼" 옵션).
     */
    private static final Duration DELIVERY_CHECK_DELAY = Duration.ofSeconds(10);

    private final DefaultMessageService messageService;
    private final String fromNumber;
    private final MeterRegistry meterRegistry;
    private final ScheduledExecutorService deliveryCheckScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sms-delivery-check");
                thread.setDaemon(true);
                return thread;
            });

    public SolapiSmsProvider(
            @Value("${sms.api-key}") String apiKey,
            @Value("${sms.api-secret}") String apiSecret,
            @Value("${sms.from-number}") String fromNumber,
            MeterRegistry meterRegistry) {
        this.fromNumber = fromNumber;
        this.meterRegistry = meterRegistry;

        if (StringUtils.hasText(apiKey) && StringUtils.hasText(apiSecret)) {
            this.messageService = NurigoApp.INSTANCE.initialize(apiKey, apiSecret, "https://api.solapi.com");
        } else {
            this.messageService = null;
        }
    }

    @PreDestroy
    void shutdownDeliveryCheckScheduler() {
        deliveryCheckScheduler.shutdownNow();
    }

    @Override
    public boolean isConfigured() {
        return messageService != null;
    }

    @Override
    public boolean isMockMode() {
        return false;
    }

    @Override
    public void sendSms(String to, String content) {
        if (messageService == null) {
            // 정상 흐름에서는 PhoneVerificationService 가 isConfigured()==false 를 먼저 보고
            // sendOtp() 단계에서 막는다. 여기 도달했다면 그 방어를 우회한 호출이므로
            // 조용히 넘기지 않고 인터페이스 계약대로 실패를 알린다.
            throw new SmsSendFailedException("Solapi 자격증명이 설정되지 않았습니다.");
        }

        Message message = new Message();
        // E.164 포맷을 국내 전화번호 형식으로 변환 (+8210... -> 010...)
        String localNumber = to.startsWith("+82") ? to.replace("+82", "0") : to;
        message.setFrom(fromNumber);
        message.setTo(localNumber);
        message.setText(content);

        try {
            SingleMessageSentResponse response = this.messageService.sendOne(new SingleMessageSendingRequest(message));
            log.info("SMS 발송 완료(접수). 수신자: {}, messageId: {}, 접수 결과: {}",
                    localNumber, response.getMessageId(), response.getStatusCode());
            scheduleDeliveryCheck(response.getMessageId(), localNumber);
        } catch (Exception e) {
            log.error("SMS 발송 실패. 수신자: {}", localNumber, e);
            throw new SmsSendFailedException("SMS 발송에 실패했습니다.", e);
        }
    }

    /**
     * 발송 요청 자체는 성공(접수)했지만 통신사가 실제로 전달했는지는 아직 모른다. 사용자 흐름은
     * 이미 끝났으니(sendSms 호출부는 여기서 안 기다린다) 결과만 나중에 로그·메트릭으로 남긴다.
     */
    private void scheduleDeliveryCheck(String messageId, String to) {
        if (messageId == null) {
            return;
        }
        deliveryCheckScheduler.schedule(
                () -> checkDelivery(messageId, to), DELIVERY_CHECK_DELAY.toSeconds(), TimeUnit.SECONDS);
    }

    private void checkDelivery(String messageId, String to) {
        try {
            MessageListRequest request = new MessageListRequest(
                    null, null, null, null, null, null, null, null, null, null, null, null);
            request.setMessageId(messageId);
            MessageListResponse response = messageService.getMessageList(request);
            Message reported = response != null && response.getMessageList() != null
                    ? response.getMessageList().get(messageId)
                    : null;

            if (reported == null) {
                log.warn("[SMS-DELIVERY-CHECK] messageId={} 조회 결과가 없습니다 — 상태를 확인할 수 없습니다.", messageId);
                return;
            }

            String statusCode = reported.getStatusCode();
            if (!STATUS_COMPLETE.equals(statusCode)) {
                meterRegistry.counter(METRIC_SEND_FAILED, "statusCode", String.valueOf(statusCode)).increment();
                log.error("[SMS-DELIVERY-FAILED] 접수는 됐지만 전달에 실패했습니다. 수신자: {}, messageId: {}, statusCode: {}",
                        to, messageId, statusCode);
            }
        } catch (Exception e) {
            // 결과 조회 자체의 실패다 — 실제 발송 실패로 오인해 사용자 흐름에 영향을 주면 안 되므로
            // sendSms() 호출부로 전파하지 않고 여기서 흡수한다.
            log.warn("[SMS-DELIVERY-CHECK] messageId={} 상태 확인 중 오류.", messageId, e);
        }
    }
}
