package com.dh.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SMS 발송 상한(auth.api#29). <b>전부 설정으로 뺀 이유는 이 값들이 지출 상한이기 때문이다</b> —
 * 공격이든 오작동이든 한밤중에 조여야 할 때 재배포를 기다릴 수 없다.
 *
 * <p><b>전역 상한은 기본적으로 꺼져 있다(2026-08-22 사용자 결정).</b> 전역 하드 캡은 지출을 묶어
 * 주는 대신 <b>자기 자신을 향한 DoS 벡터</b>가 된다 — 공격자가 전역 쿼터를 태우면 그 순간부터
 * 정상 가입자 전체가 503을 맞는다. 규모 있는 서비스가 전역 차단 대신 번호/IP/디바이스 단위 제한과
 * 캡차·알림으로 가는 이유가 그것이다. 여기서도 <b>상시 제한은 번호당 하나만</b> 건다.
 *
 * <p>전역 값은 지우지 않고 <b>비상 브레이크</b>로 남겨 뒀다. 실제 공격이 관측되면 재배포 없이
 * {@code SMS_GUARD_GLOBAL_BURST_LIMIT} 만 넣어 즉시 조일 수 있다 — 그때는 "가입이 막히는 것"이
 * "잔액이 타는 것"보다 낫기 때문이다. 평시에 그 판단을 미리 해 두지 않을 뿐이다.
 *
 * <p>평시 방어는 두 겹으로 간다: <b>번호당 상한</b>(코드) + <b>발송량 메트릭 알림</b>
 * ({@code sms.send.recorded}, Grafana). 차단이 아니라 관측이라 정상 사용자를 막지 않는다.
 *
 * <p>기본값 근거: {@code per-number-daily-limit=5} → 정상 가입은 1~2통이면 끝나고, 재발송 실패를
 * 감안한 여유다. 값을 0 이하로 두면 해당 검사를 끈다.
 */
@Component
@ConfigurationProperties(prefix = "sms.guard")
public class SmsGuardProperties {

    /** 한 번호로 {@link #dailyWindow} 동안 보낼 수 있는 최대 통수. */
    private int perNumberDailyLimit = 5;

    /**
     * 전체 서비스가 {@link #dailyWindow} 동안 보낼 수 있는 최대 통수.
     * <b>기본 0(끔)</b> — 비상시에만 켜는 브레이크다. 클래스 javadoc 참고.
     */
    private int globalDailyLimit = 0;

    /**
     * 전체 서비스가 {@link #burstWindow} 동안 보낼 수 있는 최대 통수.
     * <b>기본 0(끔)</b> — 공격이 관측됐을 때 가장 먼저 켤 값이다(창이 짧아 반응이 빠르다).
     */
    private int globalBurstLimit = 0;

    /** 일일 상한을 세는 창. 달력 하루가 아니라 롤링이라 자정 리셋을 노린 우회가 통하지 않는다. */
    private Duration dailyWindow = Duration.ofHours(24);

    /** 버스트 상한을 세는 창. */
    private Duration burstWindow = Duration.ofMinutes(10);

    /** 발송 원장 보관 기간. 상한 계산 창보다 넉넉해야 하고, 그 이상은 사고 조사용 여유분이다. */
    private Duration retention = Duration.ofDays(7);

    public int getPerNumberDailyLimit() {
        return perNumberDailyLimit;
    }

    public void setPerNumberDailyLimit(int perNumberDailyLimit) {
        this.perNumberDailyLimit = perNumberDailyLimit;
    }

    public int getGlobalDailyLimit() {
        return globalDailyLimit;
    }

    public void setGlobalDailyLimit(int globalDailyLimit) {
        this.globalDailyLimit = globalDailyLimit;
    }

    public int getGlobalBurstLimit() {
        return globalBurstLimit;
    }

    public void setGlobalBurstLimit(int globalBurstLimit) {
        this.globalBurstLimit = globalBurstLimit;
    }

    public Duration getDailyWindow() {
        return dailyWindow;
    }

    public void setDailyWindow(Duration dailyWindow) {
        this.dailyWindow = dailyWindow;
    }

    public Duration getBurstWindow() {
        return burstWindow;
    }

    public void setBurstWindow(Duration burstWindow) {
        this.burstWindow = burstWindow;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }
}
