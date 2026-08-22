package com.dh.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import com.dh.auth.service.PhoneVerificationService.SmsNotConfiguredException;
import com.dh.auth.service.sms.SmsProvider;
import com.dh.auth.service.sms.SmsSendGuard;
import com.dh.auth.service.sms.SmsSendGuard.PerNumberDailyLimitException;

@ExtendWith(MockitoExtension.class)
class PhoneVerificationServiceTest {

    @Mock
    private PhoneOtpAttemptRepository otpAttemptRepository;

    @Mock
    private PhoneVerificationRepository verificationRepository;

    @Mock
    private SmsProvider smsProvider;

    @Mock
    private SmsSendGuard smsSendGuard;

    @InjectMocks
    private PhoneVerificationService phoneVerificationService;

    private static final String TEST_PHONE = "+821012345678";

    @Test
    @DisplayName("처음 OTP를 요청하면 새로운 발송 내역이 저장되고 SMS가 발송된다.")
    void sendOtp_FirstTime() {
        // given
        when(smsProvider.isConfigured()).thenReturn(true);
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
        when(smsProvider.isConfigured()).thenReturn(true);
        PhoneOtpAttempt existingAttempt = new PhoneOtpAttempt(TEST_PHONE, "123456", LocalDateTime.now().plusMinutes(5));
        when(otpAttemptRepository.findByPhoneNumber(TEST_PHONE)).thenReturn(Optional.of(existingAttempt));

        // when & then
        assertThrows(OtpCooldownException.class, () -> phoneVerificationService.sendOtp(TEST_PHONE));
        verify(otpAttemptRepository, never()).save(any());
        verify(smsProvider, never()).sendSms(any(), any());
    }

    @Test
    @DisplayName("SMS 벤더가 설정된 상태에서 잘못된 인증 코드를 입력하면 예외가 발생하고 시도 횟수가 증가한다.")
    void verifyOtp_MismatchCode_WhenSmsConfigured() {
        // given
        PhoneOtpAttempt attempt = new PhoneOtpAttempt(TEST_PHONE, "123456", LocalDateTime.now().plusMinutes(5));
        when(otpAttemptRepository.findByPhoneNumber(TEST_PHONE)).thenReturn(Optional.of(attempt));

        // when & then
        assertThrows(OtpVerificationException.class, () -> phoneVerificationService.verifyOtp(TEST_PHONE, "000000"));
        assertThat(attempt.getAttemptCount()).isEqualTo(1);
    }

    // ── auth.api#11 회귀 방지 ────────────────────────────────────────────────
    // 예전에는 벤더 자격증명이 없으면 코드 불일치를 그대로 통과시켰고(SMS-MOCK-BYPASS),
    // 운영 클러스터에 키가 등록된 적이 없어 실제로 아무 숫자나 통과되고 있었다.

    @Test
    @DisplayName("mock 모드에서도 인증 코드가 다르면 거부된다 — 바이패스는 제거됐다")
    void verifyOtp_MismatchCode_InMockMode_IsRejected() {
        // given
        PhoneOtpAttempt attempt = new PhoneOtpAttempt(TEST_PHONE, "123456", LocalDateTime.now().plusMinutes(5));
        when(otpAttemptRepository.findByPhoneNumber(TEST_PHONE)).thenReturn(Optional.of(attempt));

        // when & then
        assertThrows(OtpVerificationException.class, () -> phoneVerificationService.verifyOtp(TEST_PHONE, "000000"));
        assertThat(attempt.getAttemptCount()).isEqualTo(1);
        verify(verificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("mock 모드에서 정확한 코드를 넣으면 통과한다 — 개발 흐름은 그대로 돌아간다")
    void verifyOtp_MatchingCode_InMockMode_Passes() {
        // given
        PhoneOtpAttempt attempt = new PhoneOtpAttempt(TEST_PHONE, "123456", LocalDateTime.now().plusMinutes(5));
        when(otpAttemptRepository.findByPhoneNumber(TEST_PHONE)).thenReturn(Optional.of(attempt));

        // when
        phoneVerificationService.verifyOtp(TEST_PHONE, "123456");

        // then
        verify(verificationRepository).save(any());
    }

    @Test
    @DisplayName("solapi 인데 자격증명이 비어 있으면 발송 단계에서 막는다 (운영 오설정)")
    void sendOtp_WhenMisconfigured_IsRejected() {
        // given
        when(smsProvider.isConfigured()).thenReturn(false);
        when(smsProvider.isMockMode()).thenReturn(false);

        // when & then
        assertThrows(SmsNotConfiguredException.class, () -> phoneVerificationService.sendOtp(TEST_PHONE));
        verify(otpAttemptRepository, never()).save(any());
        verify(smsProvider, never()).sendSms(any(), any());
    }

    @Test
    @DisplayName("mock 모드면 자격증명이 없어도 발송 단계는 통과한다 (코드는 로그로 나간다)")
    void sendOtp_InMockMode_Proceeds() {
        // given
        when(smsProvider.isConfigured()).thenReturn(false);
        when(smsProvider.isMockMode()).thenReturn(true);
        when(otpAttemptRepository.findByPhoneNumber(TEST_PHONE)).thenReturn(Optional.empty());

        // when
        phoneVerificationService.sendOtp(TEST_PHONE);

        // then
        verify(otpAttemptRepository).save(any());
        verify(smsProvider).sendSms(any(), any());
    }

    @Test
    @DisplayName("발송 상한에 걸리면 SMS도 보내지 않고 인증 세션도 만들지 않는다")
    void sendOtp_BlockedByGuard() {
        // given
        when(smsProvider.isConfigured()).thenReturn(true);
        when(otpAttemptRepository.findByPhoneNumber(TEST_PHONE)).thenReturn(Optional.empty());
        doThrow(new PerNumberDailyLimitException())
                .when(smsSendGuard).checkAndRecord(eq(TEST_PHONE), any());

        // when & then
        assertThrows(PerNumberDailyLimitException.class, () -> phoneVerificationService.sendOtp(TEST_PHONE));
        verify(otpAttemptRepository, never()).save(any());
        verify(smsProvider, never()).sendSms(any(), any());
    }

    @Test
    @DisplayName("쿨다운 중이면 상한 검사까지 가지 않는다 — 연타하는 정상 사용자에게 '일일 상한'을 보여주면 안내가 틀린다")
    void sendOtp_CooldownIsJudgedBeforeGuard() {
        // given
        when(smsProvider.isConfigured()).thenReturn(true);
        PhoneOtpAttempt existingAttempt = new PhoneOtpAttempt(TEST_PHONE, "123456", LocalDateTime.now().plusMinutes(5));
        when(otpAttemptRepository.findByPhoneNumber(TEST_PHONE)).thenReturn(Optional.of(existingAttempt));

        // when & then
        assertThrows(OtpCooldownException.class, () -> phoneVerificationService.sendOtp(TEST_PHONE));
        verify(smsSendGuard, never()).checkAndRecord(any(), any());
    }
}
