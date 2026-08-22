package com.dh.auth.service.sms;

public interface SmsProvider {
    void sendSms(String to, String content);

    /** 실제 벤더 자격증명이 설정되어 문자가 실제로 발송되는지 여부. */
    boolean isConfigured();

    /**
     * 개발/테스트용 mock 으로 <b>의도적으로</b> 설정된 상태인지.
     *
     * <p>{@link #isConfigured()} 하나로는 "일부러 mock 을 골랐다"와 "운영인데 자격증명이 비었다"를
     * 구분할 수 없다. 그 둘을 같은 값으로 뭉갠 결과가 auth.api#11 이었다 — 운영에서 키가 비면
     * 어떤 인증번호를 넣어도 통과했다. 두 상태는 대응이 정반대다:
     * mock 은 코드를 로그로 흘려 개발을 계속하게 두고, 오설정은 흐름 자체를 막아야 한다.
     */
    boolean isMockMode();
}
