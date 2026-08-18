package com.dh.auth.service.sms;

public class SmsSendFailedException extends RuntimeException {
    public SmsSendFailedException(String message) {
        super(message);
    }
    
    public SmsSendFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
