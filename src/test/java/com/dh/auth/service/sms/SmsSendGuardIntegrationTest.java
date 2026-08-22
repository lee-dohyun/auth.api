package com.dh.auth.service.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dh.auth.entity.SmsSendLog;
import com.dh.auth.repository.SmsSendLogRepository;
import com.dh.auth.service.sms.SmsSendGuard.GloballyThrottledException;
import com.dh.auth.service.sms.SmsSendGuard.PerNumberDailyLimitException;

/**
 * 발송 상한을 <b>실제 Postgres</b>에서 검증한다(auth.api#29).
 *
 * <p>단위 테스트({@link SmsSendGuardTest})는 리포지토리를 목킹하므로 "상한 로직"만 본다. 정작
 * 돈이 걸린 부분 — V8 마이그레이션과 엔티티가 맞물리는지, {@code countByPhoneNumberAndSentAtAfter}
 * 같은 파생 쿼리가 실제로 의도한 행을 세는지, 여러 번 호출이 커밋되어 누적되는지 — 는 실 DB
 * 에서만 드러난다. 상한이 조용히 풀린 사실은 청구서로만 알게 된다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 <b>일부러 붙이지 않았다</b>. 붙이면 모든 호출이 한
 * 트랜잭션에 묶여 롤백되고, "발송 건이 커밋되어 다음 호출에 보이는가"라는 이 테스트의 핵심이
 * 사라진다. 대신 매 테스트 시작 시 원장을 비운다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "sms.guard.per-number-daily-limit=3",
            "sms.guard.global-daily-limit=5",
            "sms.guard.global-burst-limit=100",
            "sms.guard.retention=1h",
        })
@Testcontainers
class SmsSendGuardIntegrationTest {

    // 이 개발 머신은 세션이 10개 이상 동시에 떠 있는 게 정상이라 컨테이너 초기 기동이
    // 기본 타임아웃(60초)보다 오래 걸릴 때가 있다.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Autowired
    private SmsSendGuard guard;

    @Autowired
    private SmsSendLogRepository sendLogRepository;

    @BeforeEach
    void clearLedger() {
        sendLogRepository.deleteAll();
    }

    @Test
    @DisplayName("같은 번호로 상한+1회 호출하면 상한 횟수만 기록되고 마지막은 막힌다")
    void 번호당_상한을_실제_DB에서_지킨다() {
        String phone = "+821012345678";

        for (int i = 0; i < 3; i++) {
            guard.checkAndRecord(phone, LocalDateTime.now());
        }

        assertThatThrownBy(() -> guard.checkAndRecord(phone, LocalDateTime.now()))
                .isInstanceOf(PerNumberDailyLimitException.class);

        assertThat(sendLogRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("전역 상한은 번호가 달라도 합산된다 — 번호를 바꿔 가며 우회할 수 없다")
    void 전역_상한은_번호를_바꿔도_합산된다() {
        // 번호당 상한(3)에 걸리지 않도록 매번 다른 번호를 쓴다. 이게 예전에 뚫려 있던 경로다.
        for (int i = 0; i < 5; i++) {
            guard.checkAndRecord("+82101234000" + i, LocalDateTime.now());
        }

        assertThatThrownBy(() -> guard.checkAndRecord("+821012340099", LocalDateTime.now()))
                .isInstanceOf(GloballyThrottledException.class);

        assertThat(sendLogRepository.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("보관 기간이 지난 원장은 정리되고, 그만큼 상한도 다시 풀린다")
    void 보관기간이_지난_원장은_정리된다() {
        String phone = "+821011112222";
        LocalDateTime longAgo = LocalDateTime.now().minusHours(2);
        for (int i = 0; i < 3; i++) {
            sendLogRepository.save(new SmsSendLog(phone, SmsSendLog.PURPOSE_OTP, longAgo));
        }

        // retention=1h 이므로 2시간 전 행 3건은 이번 호출에서 정리된다.
        guard.checkAndRecord(phone, LocalDateTime.now());

        assertThat(sendLogRepository.count()).isEqualTo(1);
    }
}
