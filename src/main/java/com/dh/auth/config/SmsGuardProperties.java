package com.dh.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SMS 발송 상한(auth.api#29). <b>전부 설정으로 뺀 이유는 이 값들이 지출 상한이기 때문이다</b> —
 * 공격이든 오작동이든 한밤중에 조여야 할 때 재배포를 기다릴 수 없다.
 *
 * <p>기본값 산정 근거(2026-08-22, Solapi 단문 SMS 18원 VAT 별도 = 19.8원 기준):
 * <ul>
 *   <li>{@code global-daily-limit=300} → 하루 최대 약 5,940원. 실제 가입 트래픽의 수십 배 여유다.</li>
 *   <li>{@code global-burst-limit=30 / 10분} → 일일 상한만 있으면 한 번에 300통이 5분 만에 타 버린다.
 *       짧은 창을 같이 둬서 폭주를 먼저 끊는다(Solapi 베이직 플랜은 5초당 100회까지 받아 준다).</li>
 *   <li>{@code per-number-daily-limit=5} → 정상 가입은 1~2통이면 끝난다. 재발송 실패를 감안한 여유.</li>
 * </ul>
 *
 * <p>값을 0 이하로 두면 해당 검사를 끈다.
 */
@Component
@ConfigurationProperties(prefix = "sms.guard")
public class SmsGuardProperties {

    /** 한 번호로 {@link #dailyWindow} 동안 보낼 수 있는 최대 통수. */
    private int perNumberDailyLimit = 5;

    /** 전체 서비스가 {@link #dailyWindow} 동안 보낼 수 있는 최대 통수 — 하루 지출의 절대 상한. */
    private int globalDailyLimit = 300;

    /** 전체 서비스가 {@link #burstWindow} 동안 보낼 수 있는 최대 통수 — 폭주 차단용. */
    private int globalBurstLimit = 30;

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
