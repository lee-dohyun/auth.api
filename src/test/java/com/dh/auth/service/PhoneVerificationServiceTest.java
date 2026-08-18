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
import com.dh.auth.entity.PhoneVerification;
import com.dh.auth.repository.PhoneOtpAttemptRepository;
import com.dh.auth.repository.PhoneVerificationRepository;
import com.dh.auth.service.PhoneVerificationService.OtpCooldownException;

@ExtendWith(MockitoExtension.class)
class PhoneVerificationServiceTest {

    @Mock
    private PhoneOtpAttemptRepository otpAttemptRepository;

    @Mock
    private PhoneVerificationRepository verificationRepository;

    @InjectMocks
    private PhoneVerificationService phoneVerificationService;

    private static final String TEST_PHONE = "+821012345678";

    @Test
    @DisplayName("처음 OTP를 요청하면 새로운 발송 내역이 저장된다.")
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
        assertThat(savedAttempt.getOtpCode().length()).isEqualTo(6);
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
    }
}
