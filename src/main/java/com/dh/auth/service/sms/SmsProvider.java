package com.dh.auth.service.sms;

public interface SmsProvider {
    void sendSms(String to, String content);
}
