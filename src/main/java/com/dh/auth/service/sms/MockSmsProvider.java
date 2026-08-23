package com.dh.auth.service.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 개발/테스트용 벤더. {@code sms.provider=mock} 일 때만 등록된다.
 *
 * <p>문자를 실제로 보내지 않고 내용을 로그로만 남긴다({@code [MOCK SMS]}) — OTP 검증은
 * 여전히 정확한 일치를 요구하므로 이 벤더가 mock이라고 인증 자체가 느슨해지지 않는다.
 */
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "mock")
public class MockSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(MockSmsProvider.class);

    @Override
    public void sendSms(String to, String content) {
        log.info("[MOCK SMS] {}로 메시지 발송: {}", to, content);
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public boolean isMockMode() {
        return true;
    }
}
