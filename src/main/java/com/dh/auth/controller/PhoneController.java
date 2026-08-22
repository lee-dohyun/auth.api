package com.dh.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.dh.auth.config.Messages;
import com.dh.auth.dto.AuthDtos.ErrorResponse;
import com.dh.auth.dto.AuthDtos.SendPhoneOtpRequest;
import com.dh.auth.dto.AuthDtos.VerifyPhoneOtpRequest;
import com.dh.auth.service.PhoneVerificationService;
import com.dh.auth.service.PhoneVerificationService.OtpCooldownException;
import com.dh.auth.service.PhoneVerificationService.OtpVerificationException;
import com.dh.auth.service.PhoneVerificationService.SmsNotConfiguredException;
import com.dh.auth.service.sms.SmsSendGuard.GloballyThrottledException;
import com.dh.auth.service.sms.SmsSendGuard.PerNumberDailyLimitException;

import jakarta.validation.Valid;

@Validated
@RestController
public class PhoneController {

    private final PhoneVerificationService phoneVerificationService;
    private final Messages messages;

    public PhoneController(PhoneVerificationService phoneVerificationService, Messages messages) {
        this.phoneVerificationService = phoneVerificationService;
        this.messages = messages;
    }

    @PostMapping("/api/auth/phone/send-otp")
    public ResponseEntity<?> sendOtp(@Valid @RequestBody SendPhoneOtpRequest request) {
        try {
            phoneVerificationService.sendOtp(request.phoneNumber());
        } catch (OtpCooldownException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse(messages.get(e.getMessageKey(), e.getMessageArgs())));
        } catch (PerNumberDailyLimitException e) {
            // 이 번호가 하루 몫을 다 쓴 것 — 시간이 지나면 풀리므로 쿨다운과 같은 429다.
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse(messages.get(e.getMessageKey())));
        } catch (SmsNotConfiguredException e) {
            // 사용자 입력 문제가 아니라 우리 쪽 설정 문제라 503이다.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse(messages.get(e.getMessageKey())));
        } catch (GloballyThrottledException e) {
            // 이 사용자가 뭘 잘못한 게 아니라 서비스가 스스로 발송을 멈춘 것 — 역시 503이다.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse(messages.get(e.getMessageKey())));
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/auth/phone/verify-otp")
    public ResponseEntity<?> verifyOtp(@Valid @RequestBody VerifyPhoneOtpRequest request) {
        try {
            phoneVerificationService.verifyOtp(request.phoneNumber(), request.code());
        } catch (OtpVerificationException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(messages.get(e.getMessageKey())));
        }
        return ResponseEntity.ok().build();
    }
}
