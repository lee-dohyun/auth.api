package com.dh.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dh.auth.entity.PhoneOtpAttempt;
import com.dh.auth.repository.PhoneOtpAttemptRepository;
import com.dh.auth.repository.PhoneVerificationRepository;
import com.dh.auth.service.PhoneVerificationService.OtpCooldownException;
import com.dh.auth.service.PhoneVerificationService.OtpVerificationException;
import com.dh.auth.service.sms.SmsProvider;

@ExtendWith(MockitoExtension.class)
class PhoneVerificationServiceTest {

    @Mock
    private PhoneOtpAttemptRepository otpAttemptRepository;

    @Mock
    private PhoneVerificationRepository verificationRepository;

    @Mock
    private SmsProvider smsProvider;

    @InjectMocks
    private PhoneVerificationService phoneVerificationService;

    private static final String TEST_PHONE = "+821012345678";

    @Test
    @DisplayName("처음 OTP를 요청하면 새로운 발송 내역이 저장되고 SMS가 발송된다.")
    void sendOtp_FirstTime() {
        // given
        when(otpAttemptRepository.findByPhoneNumber(TEST_PHONE)).thenReturn(Optional.empty());

        // when
        phoneVerificationService.sendOtp(TEST_PHONE);

        // then
        ArgumentCaptor<PhoneOtpAttempt> attemptCaptor = ArgumentCaptor.forClass(PhoneOtpAttempt.class);
        verify(otpAttemptRepository).save(attemptCaptor.capture());
        
        PhoneOtpAttempt savedAttempt = attemptCaptor.getValue();
        assertThat(savedAttempt.getPhoneNumber()).isEqualTo(TEST_PHONE);
        assertThat(savedAttempt.getOtpCode()).isNotNull();
        
        verify(smsProvider, times(1)).sendSms(TEST_PHONE, "[POSSelect] 인증번호: " + savedAttempt.getOtpCode());
    }

    @Test
    @DisplayName("쿨타임 이전에 다시 OTP를 요청하면 예외가 발생한다.")
    void sendOtp_BeforeCooldown() {
        // given
        PhoneOtpAttempt existingAttempt = new PhoneOtpAttempt(TEST_PHONE, "123456", LocalDateTime.now().plusMinutes(5));
        when(otpAttemptRepository.findByPhoneNumber(TEST_PHONE)).thenReturn(Optional.of(existingAttempt));

        // when & then
        assertThrows(OtpCooldownException.class, () -> phoneVerificationService.sendOtp(TEST_PHONE));
        verify(otpAttemptRepository, never()).save(any());
        verify(smsProvider, never()).sendSms(any(), any());
    }

    @Test
    @DisplayName("잘못된 인증 코드를 입력하면 예외가 발생하고 시도 횟수가 증가한다.")
    void verifyOtp_MismatchCode() {
        // given
        PhoneOtpAttempt attempt = new PhoneOtpAttempt(TEST_PHONE, "123456", LocalDateTime.now().plusMinutes(5));
        when(otpAttemptRepository.findByPhoneNumber(TEST_PHONE)).thenReturn(Optional.of(attempt));

        // when & then
        assertThrows(OtpVerificationException.class, () -> phoneVerificationService.verifyOtp(TEST_PHONE, "000000"));
        assertThat(attempt.getAttemptCount()).isEqualTo(1);
    }
}
