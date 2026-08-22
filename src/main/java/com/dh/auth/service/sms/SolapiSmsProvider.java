package com.dh.auth.service.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import net.nurigo.sdk.NurigoApp;
import net.nurigo.sdk.message.model.Message;
import net.nurigo.sdk.message.request.SingleMessageSendingRequest;
import net.nurigo.sdk.message.response.SingleMessageSentResponse;
import net.nurigo.sdk.message.service.DefaultMessageService;

@Component
public class SolapiSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(SolapiSmsProvider.class);
    private final DefaultMessageService messageService;
    private final String fromNumber;
    private final String providerName;

    public SolapiSmsProvider(
            @Value("${sms.api-key}") String apiKey,
            @Value("${sms.api-secret}") String apiSecret,
            @Value("${sms.from-number}") String fromNumber,
            @Value("${sms.provider:solapi}") String providerName) {
        this.fromNumber = fromNumber;
        this.providerName = providerName;
        
        if ("solapi".equalsIgnoreCase(providerName) && StringUtils.hasText(apiKey) && StringUtils.hasText(apiSecret)) {
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
        return !"solapi".equalsIgnoreCase(providerName);
    }

    @Override
    public void sendSms(String to, String content) {
        if (!"solapi".equalsIgnoreCase(providerName)) {
            log.info("[MOCK SMS] {}로 메시지 발송: {}", to, content);
            return;
        }
        
        if (messageService == null) {
            log.warn("Solapi SMS 설정이 올바르지 않습니다. 발송을 건너뜁니다.");
            return;
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
