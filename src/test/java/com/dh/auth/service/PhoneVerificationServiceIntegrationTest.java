package com.dh.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dh.auth.repository.PhoneOtpAttemptRepository;
import com.dh.auth.repository.SmsSendLogRepository;
import com.dh.auth.service.PhoneVerificationService.OtpCooldownException;
import com.dh.auth.service.sms.SmsProvider;
import com.dh.auth.service.sms.SmsSendFailedException;

/**
 * auth.api#33 — {@code sendSms()}를 트랜잭션 밖으로 뺀 것이 <b>실제로 커밋 경계를 바꿨는지</b>를
 * 실 Postgres에서 검증한다.
 *
 * <p>{@link com.dh.auth.service.PhoneVerificationServiceTest}는 리포지토리를 목킹하므로 "저장하라고
 * 시켰는가"만 본다. 트랜잭션 전파는 그 목이 대신 답해 줄 수 없는 질문이다 — 벤더 호출이 실패했을 때
 * 이미 실행된 리포지토리 호출이 진짜 커밋된 채로 남는지는 실제 트랜잭션 매니저 없이는 확인할 방법이
 * 없다(캐논 §3 "트랜잭션 전파·멱등성 변경은 단위 테스트로 검증이 성립하지 않는다", posselect #211).
 *
 * <p>{@link SmsProvider}만 {@link MockitoBean}으로 바꿔 벤더 실패를 결정적으로 재현한다 — 실제
 * Solapi는 접수(2000)를 동기로 돌려주므로 벤더 예외를 실제 운영에서 임의로 강제할 방법이 없다
 * (auth.api#34에서 이미 확인된 사실). 나머지(리포지토리·트랜잭션 매니저·Postgres)는 전부 실물이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PhoneVerificationServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Autowired
    private PhoneVerificationService phoneVerificationService;

    @Autowired
    private PhoneOtpAttemptRepository otpAttemptRepository;

    @Autowired
    private SmsSendLogRepository sendLogRepository;

    @MockitoBean
    private SmsProvider smsProvider;

    private static final String TEST_PHONE = "+821099998888";

    @BeforeEach
    void clearState() {
        otpAttemptRepository.deleteAll();
        sendLogRepository.deleteAll();
    }

    @Test
    @DisplayName("벤더 호출이 실패해도 쿨다운·발송 원장·OTP 세션은 롤백되지 않고 커밋된 채로 남는다")
    void sendOtp_VendorFailure_DoesNotRollBackAlreadyCommittedState() {
        // given
        when(smsProvider.isConfigured()).thenReturn(true);
        doThrow(new SmsSendFailedException("vendor down")).when(smsProvider).sendSms(any(), any());

        // when
        assertThatThrownBy(() -> phoneVerificationService.sendOtp(TEST_PHONE))
                .isInstanceOf(SmsSendFailedException.class);

        // then — 예전 코드였다면 이 전부가 @Transactional 롤백으로 사라졌다
        assertThat(otpAttemptRepository.findByPhoneNumber(TEST_PHONE))
                .as("벤더 호출 실패로 인증 세션 자체가 사라지면 사용자는 인증할 방법이 없어진다")
                .isPresent()
                .get()
                .satisfies(attempt -> assertThat(attempt.getOtpCode()).isNotNull());

        assertThat(sendLogRepository.countByPhoneNumberAndSentAtAfter(TEST_PHONE, LocalDateTime.now().minusMinutes(1)))
                .as("발송 원장이 사라지면 벤더가 불안정한 동안 상한이 무의미해진다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("벤더가 실패해도 쿨다운은 잠긴다 — 그 번호가 즉시 무제한 재시도할 수 없다")
    void sendOtp_VendorFailure_StillEnforcesCooldownOnRetry() {
        // given
        when(smsProvider.isConfigured()).thenReturn(true);
        doThrow(new SmsSendFailedException("vendor down")).when(smsProvider).sendSms(any(), any());

        assertThatThrownBy(() -> phoneVerificationService.sendOtp(TEST_PHONE))
                .isInstanceOf(SmsSendFailedException.class);

        // when & then — 예전 코드였다면 쿨다운 레코드까지 롤백되어 즉시 재시도가 가능했다
        assertThatThrownBy(() -> phoneVerificationService.sendOtp(TEST_PHONE))
                .as("벤더가 불안정한 상태에서 재시도 제한이 풀리면 가드(#29)의 전제가 깨진다")
                .isInstanceOf(OtpCooldownException.class);
    }
}
