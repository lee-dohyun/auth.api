package com.dh.auth.service.sms;

public interface SmsProvider {

    /**
     * 벤더 공통 규약: 실패는 예외로만 알린다. 발송 성공/실패를 boolean 이나 응답 코드로
     * 흘려보내지 말 것 — 접수(202/2000)와 전달을 같은 것으로 다뤄서 실패가 조용해지는 사고가
     * 있었다(auth.api#34). E.164 → 벤더별 번호 포맷 변환은 각 구현이 담당한다.
     *
     * @throws SmsSendFailedException 벤더 호출 자체가 실패했거나(네트워크/인증 등) 벤더가
     *     설정되지 않은 상태에서 호출된 경우. 호출 전에 {@link #isConfigured()} /
     *     {@link #isMockMode()} 로 먼저 걸러야 정상 흐름에서는 도달하지 않는다.
     */
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
