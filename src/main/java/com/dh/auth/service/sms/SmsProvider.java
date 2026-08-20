package com.dh.auth.service.sms;

public interface SmsProvider {
    void sendSms(String to, String content);

    /** 실제 벤더 자격증명이 설정되어 문자가 실제로 발송되는지 여부. */
    boolean isConfigured();
}
