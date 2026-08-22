package com.dh.auth.service.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dh.auth.config.SmsGuardProperties;
import com.dh.auth.entity.SmsSendLog;
import com.dh.auth.repository.SmsSendLogRepository;
import com.dh.auth.service.sms.SmsSendGuard.GloballyThrottledException;
import com.dh.auth.service.sms.SmsSendGuard.PerNumberDailyLimitException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 이 가드는 "기능"이 아니라 <b>지출 상한</b>이다 — 통과 조건보다 <b>막히는 조건</b>을 촘촘히 고정한다.
 * 상한이 조용히 풀리면 그 사실은 청구서로만 드러난다(auth.api#29).
 */
@ExtendWith(MockitoExtension.class)
class SmsSendGuardTest {

    private static final String PHONE = "+821012345678";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 22, 15, 0);

    @Mock
    private SmsSendLogRepository sendLogRepository;

    private SmsGuardProperties properties;
    private MeterRegistry meterRegistry;
    private SmsSendGuard guard;

    private LocalDateTime dailyFrom;
    private LocalDateTime burstFrom;

    @BeforeEach
    void setUp() {
        properties = new SmsGuardProperties();
        properties.setPerNumberDailyLimit(5);
        properties.setGlobalDailyLimit(300);
        properties.setGlobalBurstLimit(30);
        properties.setDailyWindow(Duration.ofHours(24));
        properties.setBurstWindow(Duration.ofMinutes(10));
        properties.setRetention(Duration.ofDays(7));

        meterRegistry = new SimpleMeterRegistry();
        guard = new SmsSendGuard(sendLogRepository, properties, meterRegistry);

        dailyFrom = NOW.minus(properties.getDailyWindow());
        burstFrom = NOW.minus(properties.getBurstWindow());
    }

    @Test
    @DisplayName("상한 안이면 통과하고 발송 원장에 1건 기록된다")
    void 상한_안이면_기록하고_통과한다() {
        when(sendLogRepository.countByPhoneNumberAndSentAtAfter(PHONE, dailyFrom)).thenReturn(4L);
        when(sendLogRepository.countBySentAtAfter(burstFrom)).thenReturn(29L);
        when(sendLogRepository.countBySentAtAfter(dailyFrom)).thenReturn(299L);

        guard.checkAndRecord(PHONE, NOW);

        ArgumentCaptor<SmsSendLog> captor = ArgumentCaptor.forClass(SmsSendLog.class);
        verify(sendLogRepository).save(captor.capture());
        assertThat(captor.getValue().getPhoneNumber()).isEqualTo(PHONE);
        assertThat(captor.getValue().getSentAt()).isEqualTo(NOW);
        assertThat(meterRegistry.counter("sms.send.recorded").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("번호당 일일 상한에 도달하면 429용 예외가 나가고 원장에 기록하지 않는다")
    void 번호당_일일_상한() {
        when(sendLogRepository.countByPhoneNumberAndSentAtAfter(PHONE, dailyFrom)).thenReturn(5L);

        assertThatThrownBy(() -> guard.checkAndRecord(PHONE, NOW))
                .isInstanceOf(PerNumberDailyLimitException.class);

        verify(sendLogRepository, never()).save(any());
        assertThat(meterRegistry.counter("sms.send.blocked", "reason", "per_number_daily").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("전역 버스트 상한을 넘으면 503용 예외가 나간다 — 짧은 창으로 폭주를 먼저 끊는다")
    void 전역_버스트_상한() {
        when(sendLogRepository.countByPhoneNumberAndSentAtAfter(PHONE, dailyFrom)).thenReturn(0L);
        when(sendLogRepository.countBySentAtAfter(burstFrom)).thenReturn(30L);

        assertThatThrownBy(() -> guard.checkAndRecord(PHONE, NOW))
                .isInstanceOf(GloballyThrottledException.class);

        verify(sendLogRepository, never()).save(any());
        assertThat(meterRegistry.counter("sms.send.blocked", "reason", "global_burst").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("전역 일일 상한을 넘으면 503용 예외가 나간다 — 하루 지출의 절대 상한")
    void 전역_일일_상한() {
        when(sendLogRepository.countByPhoneNumberAndSentAtAfter(PHONE, dailyFrom)).thenReturn(0L);
        when(sendLogRepository.countBySentAtAfter(burstFrom)).thenReturn(0L);
        when(sendLogRepository.countBySentAtAfter(dailyFrom)).thenReturn(300L);

        assertThatThrownBy(() -> guard.checkAndRecord(PHONE, NOW))
                .isInstanceOf(GloballyThrottledException.class);

        verify(sendLogRepository, never()).save(any());
        assertThat(meterRegistry.counter("sms.send.blocked", "reason", "global_daily").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("번호당 상한을 먼저 본다 — 한 번호의 남용이 전역 상한을 태워 다른 사용자를 막지 않게")
    void 번호당_상한이_전역보다_먼저_판정된다() {
        when(sendLogRepository.countByPhoneNumberAndSentAtAfter(PHONE, dailyFrom)).thenReturn(5L);

        assertThatThrownBy(() -> guard.checkAndRecord(PHONE, NOW))
                .isInstanceOf(PerNumberDailyLimitException.class);

        verify(sendLogRepository, never()).countBySentAtAfter(any());
    }

    @Test
    @DisplayName("보관 기간이 지난 발송 원장은 정리된다 — 상한 계산에 쓰지 않는 데이터를 쌓아 두지 않는다")
    void 오래된_원장은_정리된다() {
        when(sendLogRepository.countByPhoneNumberAndSentAtAfter(PHONE, dailyFrom)).thenReturn(0L);
        when(sendLogRepository.countBySentAtAfter(any())).thenReturn(0L);

        guard.checkAndRecord(PHONE, NOW);

        verify(sendLogRepository).deleteBySentAtBefore(eq(NOW.minus(properties.getRetention())));
    }

    @Test
    @DisplayName("상한을 0 이하로 두면 그 검사는 꺼진 것으로 본다 — 운영 중 스위치")
    void 상한_0이하는_검사를_끈다() {
        properties.setPerNumberDailyLimit(0);
        properties.setGlobalBurstLimit(0);
        properties.setGlobalDailyLimit(0);

        guard.checkAndRecord(PHONE, NOW);

        verify(sendLogRepository).save(any());
        verify(sendLogRepository, never()).countByPhoneNumberAndSentAtAfter(any(), any());
        verify(sendLogRepository, never()).countBySentAtAfter(any());
    }

    @Test
    @DisplayName("기본값은 번호당 상한만 켜져 있다 — 전역 하드 캡은 자기 자신을 향한 DoS 라 평시엔 끈다")
    void 기본값은_번호당만_켜져_있다() {
        SmsGuardProperties defaults = new SmsGuardProperties();

        assertThat(defaults.getPerNumberDailyLimit()).isPositive();
        // 전역 값이 켜져 있으면 공격자가 쿼터를 태워 정상 가입자 전체를 막을 수 있다.
        // 지출 상한은 애플리케이션이 아니라 "충전 잔액"으로 관리한다(2026-08-22 사용자 결정).
        assertThat(defaults.getGlobalDailyLimit()).isNotPositive();
        assertThat(defaults.getGlobalBurstLimit()).isNotPositive();
    }
}
