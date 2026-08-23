package com.dh.auth.service.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import net.nurigo.sdk.NurigoApp;
import net.nurigo.sdk.message.model.Message;
import net.nurigo.sdk.message.request.SingleMessageSendingRequest;
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
    private final DefaultMessageService messageService;
    private final String fromNumber;

    public SolapiSmsProvider(
            @Value("${sms.api-key}") String apiKey,
            @Value("${sms.api-secret}") String apiSecret,
            @Value("${sms.from-number}") String fromNumber) {
        this.fromNumber = fromNumber;

        if (StringUtils.hasText(apiKey) && StringUtils.hasText(apiSecret)) {
            this.messageService = NurigoApp.INSTANCE.initialize(apiKey, apiSecret, "https://api.solapi.com");
        } else {
            this.messageService = null;
        }
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
            log.info("SMS 발송 완료. 수신자: {}, 결과: {}", localNumber, response.getStatusCode());
        } catch (Exception e) {
            log.error("SMS 발송 실패. 수신자: {}", localNumber, e);
            throw new SmsSendFailedException("SMS 발송에 실패했습니다.", e);
        }
    }
}
