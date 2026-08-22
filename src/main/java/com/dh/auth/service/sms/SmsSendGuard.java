package com.dh.auth.service.sms;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.auth.config.SmsGuardProperties;
import com.dh.auth.entity.SmsSendLog;
import com.dh.auth.repository.SmsSendLogRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * SMS 발송 상한(auth.api#29). <b>기능이 아니라 지출 상한이다.</b>
 *
 * <p>{@code POST /api/auth/phone/send-otp} 는 gateway {@code PUBLIC_EXACT_PATHS} 에 등록된 공개
 * 엔드포인트다. 여기에 걸려 있던 유일한 방어가 "같은 번호 60초 쿨다운"이었는데, 그건 <b>번호를
 * 바꾸면 그만</b>이다. 벤더 자격증명이 없어 503으로 막혀 있는 동안(#14)은 드러나지 않았지만,
 * 자격증명을 넣는 순간(#13) 그 구멍은 그대로 청구서가 된다 — Solapi 베이직 플랜은 5초당 100회를
 * 받아 주므로 단문 18원 기준 분당 2만원대, 한 시간이면 백만원대다.
 *
 * <p>이 가드는 <b>벤더 위</b>에 있다. Solapi 가 다른 벤더로 교체돼도(#30) 여기는 그대로다 —
 * 상한은 벤더 SDK 의 성질이 아니라 우리 지갑의 성질이기 때문이다.
 *
 * <p>판정 순서는 <b>번호당 → 전역 버스트 → 전역 일일</b>이다. 번호당을 먼저 봐야 한 번호의 남용이
 * 전역 카운터를 태워 정상 사용자까지 막는 일이 없다.
 */
@Service
public class SmsSendGuard {

    private static final Logger log = LoggerFactory.getLogger(SmsSendGuard.class);

    private static final String METRIC_RECORDED = "sms.send.recorded";
    private static final String METRIC_BLOCKED = "sms.send.blocked";

    private final SmsSendLogRepository sendLogRepository;
    private final SmsGuardProperties properties;
    private final MeterRegistry meterRegistry;

    public SmsSendGuard(
            SmsSendLogRepository sendLogRepository,
            SmsGuardProperties properties,
            MeterRegistry meterRegistry) {
        this.sendLogRepository = sendLogRepository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /** 번호당 상한 초과. 사용자가 다시 시도해서 풀 수 있는 상태라 429로 나간다. */
    public static class PerNumberDailyLimitException extends RuntimeException {

        public PerNumberDailyLimitException() {
            super("otp.dailyLimitExceeded");
        }

        public String getMessageKey() {
            return "otp.dailyLimitExceeded";
        }
    }

    /**
     * 전역 상한 초과. 이 사용자가 뭘 잘못한 게 아니라 <b>서비스가 스스로를 막은 것</b>이라 503이다.
     * 이 예외가 실제로 나갔다는 것은 공격이거나 우리 쪽 오작동이라는 뜻이므로 알림 대상이다.
     */
    public static class GloballyThrottledException extends RuntimeException {

        public GloballyThrottledException() {
            super("otp.serviceBusy");
        }

        public String getMessageKey() {
            return "otp.serviceBusy";
        }
    }

    /**
     * 상한을 확인하고, 통과하면 발송 원장에 즉시 기록한다.
     *
     * <p><b>확인과 기록을 한 메서드로 묶은 이유</b>: 나눠 두면 호출부가 기록을 빠뜨릴 수 있고,
     * 빠뜨린 사실은 청구서로만 드러난다. 그리고 기록은 실제 발송 <b>전</b>에 한다 — 벤더 호출이
     * 실패했을 때 과다 계상되는 쪽이, 성공했는데 세지 못하는 쪽보다 안전하다.
     *
     * @throws PerNumberDailyLimitException 이 번호가 일일 상한에 도달
     * @throws GloballyThrottledException   전역 버스트/일일 상한에 도달
     */
    // REQUIRED(기본) — 호출부(PhoneVerificationService.sendOtp)의 트랜잭션에 그대로 합류한다.
    // 여기 붙이는 이유는 전파를 바꾸려는 게 아니라, 파생 delete 쿼리(deleteBySentAtBefore)가
    // 활성 트랜잭션을 요구하기 때문이다 — 없으면 트랜잭션 밖에서 부른 호출자가 런타임에 터진다.
    @Transactional
    public void checkAndRecord(String phoneNumber, LocalDateTime now) {
        purgeExpired(now);

        LocalDateTime dailyFrom = now.minus(properties.getDailyWindow());

        if (properties.getPerNumberDailyLimit() > 0) {
            long perNumber = sendLogRepository.countByPhoneNumberAndSentAtAfter(phoneNumber, dailyFrom);
            if (perNumber >= properties.getPerNumberDailyLimit()) {
                block("per_number_daily",
                        "번호당 일일 발송 상한 도달 - phone={}, sent={}, limit={}",
                        phoneNumber, perNumber, properties.getPerNumberDailyLimit());
                throw new PerNumberDailyLimitException();
            }
        }

        if (properties.getGlobalBurstLimit() > 0) {
            LocalDateTime burstFrom = now.minus(properties.getBurstWindow());
            long burst = sendLogRepository.countBySentAtAfter(burstFrom);
            if (burst >= properties.getGlobalBurstLimit()) {
                block("global_burst",
                        "전역 버스트 발송 상한 도달 - window={}, sent={}, limit={}",
                        properties.getBurstWindow(), burst, properties.getGlobalBurstLimit());
                throw new GloballyThrottledException();
            }
        }

        if (properties.getGlobalDailyLimit() > 0) {
            long daily = sendLogRepository.countBySentAtAfter(dailyFrom);
            if (daily >= properties.getGlobalDailyLimit()) {
                block("global_daily",
                        "전역 일일 발송 상한 도달 - window={}, sent={}, limit={}",
                        properties.getDailyWindow(), daily, properties.getGlobalDailyLimit());
                throw new GloballyThrottledException();
            }
        }

        sendLogRepository.save(new SmsSendLog(phoneNumber, SmsSendLog.PURPOSE_OTP, now));
        meterRegistry.counter(METRIC_RECORDED).increment();
    }

    private void purgeExpired(LocalDateTime now) {
        sendLogRepository.deleteBySentAtBefore(now.minus(properties.getRetention()));
    }

    /**
     * 막힌 사실은 <b>메트릭과 ERROR 로그 양쪽</b>에 남긴다. 메트릭만 두면 대시보드를 볼 때까지
     * 모르고, 로그만 두면 알림을 걸 수 없다.
     */
    private void block(String reason, String message, Object... args) {
        meterRegistry.counter(METRIC_BLOCKED, "reason", reason).increment();
        log.error("[SMS-GUARD:{}] " + message, prepend(reason, args));
    }

    private static Object[] prepend(String first, Object[] rest) {
        Object[] merged = new Object[rest.length + 1];
        merged[0] = first;
        System.arraycopy(rest, 0, merged, 1, rest.length);
        return merged;
    }
}
