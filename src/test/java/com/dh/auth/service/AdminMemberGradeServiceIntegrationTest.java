package com.dh.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dh.auth.config.OrderApiClient;
import com.dh.auth.dto.AdminMemberGradeDtos.GradeAdjustRequest;
import com.dh.auth.dto.AdminMemberGradeDtos.GradeAdjustResponse;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicy;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicyCreateRequest;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicyUpdateRequest;
import com.dh.auth.dto.AdminMemberGradeDtos.MemberGradeDetail;
import com.dh.auth.entity.Member;
import com.dh.auth.entity.MemberGrade;
import com.dh.auth.entity.MemberGradeHistory;
import com.dh.auth.repository.MemberGradeHistoryRepository;
import com.dh.auth.repository.MemberGradeRepository;
import com.dh.auth.repository.MemberRepository;

/**
 * 관리자 등급 정책 CRUD·회원 등급 수동 조정의 실제 DB 상태 변화 검증 (gateway#80).
 *
 * <p>멱등성("같은 요청 2회 → 1회만 반영")과 FK 로 막히는 삭제는 리포지토리를 목킹해서는 검증이
 * 성립하지 않는다(캐논 §3). Testcontainers 로 실제 Postgres 에 Flyway(V1~)를 적용해 돌린다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 붙이면 서비스 트랜잭션이 테스트
 * 트랜잭션에 합류해 "정말 커밋됐는지"를 볼 수 없다. 컨테이너는 이 클래스 전용이라 등급 마스터를
 * 바꾸는 테스트는 끝에서 스스로 원복한다(테스트 실행 순서에 기대지 않기 위해).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AdminMemberGradeServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));

    /** 월 배치가 부르는 order.api 는 테스트에 없다 — 집계 응답만 바꿔 끼운다. */
    @MockitoBean
    private OrderApiClient orderApiClient;

    @Autowired
    private AdminMemberGradeService service;

    @Autowired
    private MemberGradeRecalculationService recalculationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberGradeRepository memberGradeRepository;

    @Autowired
    private MemberGradeHistoryRepository memberGradeHistoryRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // ───────────── 등급 정책 ─────────────

    @Test
    @DisplayName("목록은 V1/V8 이 시드한 4개 등급을 정렬 순서대로, 순번 PK 없이 돌려준다")
    void 정책_목록() {
        List<GradePolicy> policies = service.listPolicies();

        assertThat(policies).extracting(GradePolicy::code)
                .containsSubsequence("GENERAL", "SILVER", "GOLD", "VIP");
        GradePolicy general = policies.stream().filter(p -> p.code().equals("GENERAL")).findFirst().orElseThrow();
        assertThat(general.isDefault()).isTrue();
        assertThat(general.minSpendAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("기준액·할인율 수정은 실제로 커밋되고, 같은 값으로 다시 불러도 결과가 같다")
    void 정책_수정은_멱등이다() {
        var request = new GradePolicyUpdateRequest("실버", new BigDecimal("3.50"), new BigDecimal("350000"), 2);
        try {
            service.updatePolicy("SILVER", request);
            GradePolicy second = service.updatePolicy("SILVER", request);

            assertThat(second.discountRate()).isEqualByComparingTo("3.50");
            MemberGrade stored = memberGradeRepository.findByCode("SILVER").orElseThrow();
            assertThat(stored.getDiscountRate()).isEqualByComparingTo("3.50");
            assertThat(stored.getMinSpendAmount()).isEqualByComparingTo("350000");
        } finally {
            service.updatePolicy("SILVER",
                    new GradePolicyUpdateRequest("실버", new BigDecimal("2.00"), new BigDecimal("300000"), 2));
        }
    }

    @Test
    @DisplayName("기본 등급의 기준액은 0원에서 바꿀 수 없다 — 바뀌면 배치가 그 미만 회원을 어느 등급에도 못 넣는다")
    void 기본_등급_기준액은_0원_고정() {
        assertThatThrownBy(() -> service.updatePolicy("GENERAL",
                new GradePolicyUpdateRequest("일반", BigDecimal.ZERO, new BigDecimal("1000"), 1)))
                .isInstanceOfSatisfying(AdminMemberGradeException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(memberGradeRepository.findByCode("GENERAL").orElseThrow().getMinSpendAmount())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("할인율 범위(0~100)·음수 기준액은 400 으로 거부되고 DB 는 그대로다")
    void 정책_입력_검증() {
        assertThatThrownBy(() -> service.updatePolicy("GOLD",
                new GradePolicyUpdateRequest("골드", new BigDecimal("100.01"), new BigDecimal("1000000"), 3)))
                .isInstanceOf(AdminMemberGradeException.class);
        assertThatThrownBy(() -> service.updatePolicy("GOLD",
                new GradePolicyUpdateRequest("골드", new BigDecimal("5"), new BigDecimal("-1"), 3)))
                .isInstanceOf(AdminMemberGradeException.class);

        assertThat(memberGradeRepository.findByCode("GOLD").orElseThrow().getDiscountRate())
                .isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("다른 등급과 같은 기준액은 409 — 배치가 둘 중 무엇을 줄지 정할 수 없다")
    void 기준액_중복_거부() {
        assertThatThrownBy(() -> service.updatePolicy("GOLD",
                new GradePolicyUpdateRequest("골드", new BigDecimal("5"), new BigDecimal("300000"), 3)))
                .isInstanceOfSatisfying(AdminMemberGradeException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("없는 등급 수정은 404")
    void 없는_등급_수정() {
        assertThatThrownBy(() -> service.updatePolicy("NOPE",
                new GradePolicyUpdateRequest("x", BigDecimal.ONE, null, 9)))
                .isInstanceOfSatisfying(AdminMemberGradeException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("등급 생성: 같은 요청 2회 → 행은 1개, 같은 코드에 다른 값이면 409")
    void 정책_생성은_멱등이다() {
        var request = new GradePolicyCreateRequest(
                "VVIP", "VVIP", new BigDecimal("15.00"), new BigDecimal("10000000"), 5);
        try {
            var first = service.createPolicy(request);
            var second = service.createPolicy(request);

            assertThat(first.created()).isTrue();
            assertThat(second.created()).isFalse();
            assertThat(memberGradeRepository.findAll().stream().filter(g -> g.getCode().equals("VVIP"))).hasSize(1);
            assertThat(memberGradeRepository.findByCode("VVIP").orElseThrow().isDefault()).isFalse();

            assertThatThrownBy(() -> service.createPolicy(new GradePolicyCreateRequest(
                    "VVIP", "VVIP", new BigDecimal("20.00"), new BigDecimal("10000000"), 5)))
                    .isInstanceOfSatisfying(AdminMemberGradeException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        } finally {
            memberGradeRepository.findByCode("VVIP").ifPresent(memberGradeRepository::delete);
        }
    }

    @Test
    @DisplayName("등급 삭제: 아무도 쓰지 않는 등급은 지워지고, 다시 지우면 404")
    void 정책_삭제() {
        service.createPolicy(new GradePolicyCreateRequest("TEMP", "임시", BigDecimal.ZERO, null, 9));

        service.deletePolicy("TEMP");

        assertThat(memberGradeRepository.findByCode("TEMP")).isEmpty();
        assertThatThrownBy(() -> service.deletePolicy("TEMP"))
                .isInstanceOfSatisfying(AdminMemberGradeException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("기본 등급과, 회원·이력이 참조하는 등급은 삭제가 409 로 막히고 행이 남는다")
    void 쓰이는_등급은_삭제_불가() {
        String sub = UUID.randomUUID().toString();
        givenMember(sub);
        service.adjustMemberGrade(sub, new GradeAdjustRequest("GOLD", "삭제 차단 검증"), "tester@posselect.com");

        assertThatThrownBy(() -> service.deletePolicy("GENERAL"))
                .isInstanceOfSatisfying(AdminMemberGradeException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.deletePolicy("GOLD"))
                .isInstanceOfSatisfying(AdminMemberGradeException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(memberGradeRepository.findByCode("GENERAL")).isPresent();
        assertThat(memberGradeRepository.findByCode("GOLD")).isPresent();
    }

    // ───────────── 회원 등급 조회·수동 조정 ─────────────

    @Test
    @DisplayName("회원 등급 조회: 현재 등급과 이력(최신 순)을 돌려주고, 로컬에 없는 회원은 404")
    void 회원_등급_조회() {
        String sub = UUID.randomUUID().toString();
        givenMember(sub);

        MemberGradeDetail detail = service.getMemberGrade(sub);

        assertThat(detail.keycloakUserId()).isEqualTo(sub);
        assertThat(detail.grade().code()).isEqualTo("GENERAL");
        assertThat(detail.history()).hasSize(1);
        assertThat(detail.history().get(0).reason()).isEqualTo("회원가입 기본 등급 부여");

        assertThatThrownBy(() -> service.getMemberGrade(UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(AdminMemberGradeException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("수동 조정: 같은 요청 2회 → 등급 변경 1회, 이력도 1건만 늘어난다")
    void 수동_조정은_멱등이다() {
        String sub = UUID.randomUUID().toString();
        givenMember(sub);
        var request = new GradeAdjustRequest("VIP", "CS 보상");

        GradeAdjustResponse first = service.adjustMemberGrade(sub, request, "tester@posselect.com");
        GradeAdjustResponse second = service.adjustMemberGrade(sub, request, "tester@posselect.com");

        assertThat(first.changed()).isTrue();
        assertThat(second.changed()).isFalse();

        MemberGradeDetail detail = service.getMemberGrade(sub);
        assertThat(detail.grade().code()).isEqualTo("VIP");
        assertThat(detail.history()).hasSize(2); // 가입 1 + 수동 조정 1
        assertThat(detail.history().get(0).gradeCode()).isEqualTo("VIP");
        assertThat(detail.history().get(0).reason()).isEqualTo("수동 조정: CS 보상");
    }

    @Test
    @DisplayName("같은 수동 조정이 동시에 2건 들어와도 이력은 1건만 늘어난다 (회원 행 잠금)")
    void 동시_수동_조정() throws Exception {
        String sub = UUID.randomUUID().toString();
        givenMember(sub);
        var request = new GradeAdjustRequest("GOLD", "동시 요청");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<GradeAdjustResponse>> futures = List.of(
                    pool.submit(() -> { start.await(); return service.adjustMemberGrade(sub, request, "a@posselect.com"); }),
                    pool.submit(() -> { start.await(); return service.adjustMemberGrade(sub, request, "b@posselect.com"); }));
            start.countDown();
            int changed = 0;
            for (Future<GradeAdjustResponse> f : futures) {
                if (f.get().changed()) {
                    changed++;
                }
            }
            assertThat(changed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(service.getMemberGrade(sub).history()).hasSize(2);
    }

    @Test
    @DisplayName("사유가 없거나 80자를 넘으면 400, 없는 등급은 404 — 회원 등급은 그대로다")
    void 수동_조정_입력_검증() {
        String sub = UUID.randomUUID().toString();
        givenMember(sub);

        assertThatThrownBy(() -> service.adjustMemberGrade(sub, new GradeAdjustRequest("VIP", "  "), "t@posselect.com"))
                .isInstanceOfSatisfying(AdminMemberGradeException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.adjustMemberGrade(
                sub, new GradeAdjustRequest("VIP", "가".repeat(81)), "t@posselect.com"))
                .isInstanceOfSatisfying(AdminMemberGradeException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.adjustMemberGrade(sub, new GradeAdjustRequest("NOPE", "사유"), "t@posselect.com"))
                .isInstanceOfSatisfying(AdminMemberGradeException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        MemberGradeDetail detail = service.getMemberGrade(sub);
        assertThat(detail.grade().code()).isEqualTo("GENERAL");
        assertThat(detail.history()).hasSize(1);
    }

    // ───────────── 등급 고정 (auth.api#49) ─────────────

    @Test
    @DisplayName("수동 조정한 등급은 유지 기한까지 월 배치가 건너뛴다 — 구매확정액이 없어도 VIP 가 그대로다")
    void 유지_기한까지_배치가_건너뛴다() {
        String sub = UUID.randomUUID().toString();
        givenMember(sub);
        LocalDate until = today().plusDays(30);
        service.adjustMemberGrade(sub, new GradeAdjustRequest("VIP", "CS 보상", until), "t@posselect.com");
        when(orderApiClient.fetchConfirmedPurchases(any())).thenReturn(Map.of());

        var result = recalculationService.recalculateAll();

        MemberGradeDetail detail = service.getMemberGrade(sub);
        assertThat(detail.grade().code()).isEqualTo("VIP");
        assertThat(detail.lock().locked()).isTrue();
        assertThat(detail.lock().until()).isEqualTo(until);
        assertThat(detail.history()).hasSize(2); // 가입 → 수동 조정. 재산정 이력은 생기지 않는다
        assertThat(result.skippedLocked()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("유지 기한을 비우면 해제할 때까지 고정된다")
    void 기한_없는_고정은_무기한이다() {
        String sub = UUID.randomUUID().toString();
        givenMember(sub);
        service.adjustMemberGrade(sub, new GradeAdjustRequest("GOLD", "무기한 고정"), "t@posselect.com");
        when(orderApiClient.fetchConfirmedPurchases(any())).thenReturn(Map.of());

        recalculationService.recalculateAll();

        MemberGradeDetail detail = service.getMemberGrade(sub);
        assertThat(detail.grade().code()).isEqualTo("GOLD");
        assertThat(detail.lock().locked()).isTrue();
        assertThat(detail.lock().until()).isNull();
    }

    @Test
    @DisplayName("유지 기한 당일까지는 고정이고, 지나면 배치가 다시 계산하고 고정 표시를 지운다")
    void 기한이_지나면_배치가_다시_계산한다() {
        String sub = UUID.randomUUID().toString();
        Member member = givenMember(sub);
        service.adjustMemberGrade(sub, new GradeAdjustRequest("VIP", "기한 검증", today()), "t@posselect.com");
        when(orderApiClient.fetchConfirmedPurchases(any())).thenReturn(Map.of());

        recalculationService.recalculateAll();
        assertThat(service.getMemberGrade(sub).grade().code())
                .as("기한 당일은 아직 고정이다")
                .isEqualTo("VIP");

        // 하루가 지난 상태를 만든다 — 과거 날짜는 API 로는 넣을 수 없어 행을 직접 고친다.
        jdbcTemplate.update("UPDATE members SET grade_locked_until = ? WHERE id = ?",
                java.sql.Date.valueOf(today().minusDays(1)), member.getId());
        assertThat(service.getMemberGrade(sub).lock().locked())
                .as("기한이 지난 고정은 배치가 돌기 전에도 유효한 고정이 아니다")
                .isFalse();

        recalculationService.recalculateAll();

        MemberGradeDetail detail = service.getMemberGrade(sub);
        assertThat(detail.grade().code()).isEqualTo("GENERAL");
        assertThat(detail.history().get(0).reason()).contains("재산정").contains("VIP -> GENERAL");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT grade_locked FROM members WHERE id = ?", Boolean.class, member.getId())).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT grade_locked_until FROM members WHERE id = ?", java.sql.Date.class, member.getId())).isNull();
    }

    @Test
    @DisplayName("고정 해제: 등급은 그대로 두고 고정만 풀어 다음 배치부터 다시 계산된다. 같은 해제 2회 → 이력 1건")
    void 고정_해제() {
        String sub = UUID.randomUUID().toString();
        givenMember(sub);
        service.adjustMemberGrade(sub, new GradeAdjustRequest("VIP", "해제 검증"), "t@posselect.com");

        GradeAdjustResponse first = service.releaseGradeLock(sub, "t@posselect.com");
        GradeAdjustResponse second = service.releaseGradeLock(sub, "t@posselect.com");

        assertThat(first.changed()).isTrue();
        assertThat(second.changed()).isFalse();
        MemberGradeDetail released = service.getMemberGrade(sub);
        assertThat(released.grade().code()).isEqualTo("VIP");
        assertThat(released.lock().locked()).isFalse();
        assertThat(released.history()).hasSize(3); // 가입 → 수동 조정 → 고정 해제
        assertThat(released.history().get(0).reason()).isEqualTo("수동 조정: 고정 해제");

        when(orderApiClient.fetchConfirmedPurchases(any())).thenReturn(Map.of());
        recalculationService.recalculateAll();
        assertThat(service.getMemberGrade(sub).grade().code()).isEqualTo("GENERAL");
    }

    @Test
    @DisplayName("같은 등급이라도 유지 기한이 다르면 기한만 바뀌고, 완전히 같은 요청은 아무것도 바꾸지 않는다")
    void 같은_등급_기한_변경() {
        String sub = UUID.randomUUID().toString();
        givenMember(sub);
        LocalDate first = today().plusDays(10);
        LocalDate extended = today().plusDays(40);
        service.adjustMemberGrade(sub, new GradeAdjustRequest("GOLD", "최초", first), "t@posselect.com");

        GradeAdjustResponse changed = service.adjustMemberGrade(
                sub, new GradeAdjustRequest("GOLD", "기한 연장", extended), "t@posselect.com");
        GradeAdjustResponse replay = service.adjustMemberGrade(
                sub, new GradeAdjustRequest("GOLD", "기한 연장", extended), "t@posselect.com");

        assertThat(changed.changed()).isTrue();
        assertThat(replay.changed()).isFalse();
        MemberGradeDetail detail = service.getMemberGrade(sub);
        assertThat(detail.lock().until()).isEqualTo(extended);
        assertThat(detail.history()).hasSize(3); // 가입 → 최초 조정 → 기한 연장
        assertThat(detail.history().get(0).reason()).isEqualTo("수동 조정: 기한 연장");
    }

    @Test
    @DisplayName("지난 날짜를 유지 기한으로 주면 400 이고 회원은 그대로다")
    void 지난_유지_기한은_거부() {
        String sub = UUID.randomUUID().toString();
        givenMember(sub);

        assertThatThrownBy(() -> service.adjustMemberGrade(
                sub, new GradeAdjustRequest("VIP", "과거 기한", today().minusDays(1)), "t@posselect.com"))
                .isInstanceOfSatisfying(AdminMemberGradeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getError()).isEqualTo("LOCK_UNTIL_IN_PAST");
                });

        MemberGradeDetail detail = service.getMemberGrade(sub);
        assertThat(detail.grade().code()).isEqualTo("GENERAL");
        assertThat(detail.lock().locked()).isFalse();
    }

    @Test
    @DisplayName("수동 조정한 적 없는 회원은 고정이 아니고, 배치가 평소대로 계산한다")
    void 고정되지_않은_회원은_그대로_재산정된다() {
        String sub = UUID.randomUUID().toString();
        givenMember(sub);
        when(orderApiClient.fetchConfirmedPurchases(any())).thenReturn(Map.of(sub, new BigDecimal("1500000")));

        recalculationService.recalculateAll();

        MemberGradeDetail detail = service.getMemberGrade(sub);
        assertThat(detail.grade().code()).isEqualTo("GOLD");
        assertThat(detail.lock().locked()).isFalse();
    }

    /** 등급 정책의 "오늘"은 KST 다(배치가 KST 로 돈다). */
    private static LocalDate today() {
        return LocalDate.now(MemberGradeRecalculationService.GRADE_ZONE);
    }

    /** 회원가입과 같은 상태(기본 등급 + 가입 이력 1건)의 회원을 실제로 저장한다. */
    private Member givenMember(String keycloakUserId) {
        MemberGrade grade = memberGradeRepository.findByIsDefaultTrue().orElseThrow();
        Member member = memberRepository.saveAndFlush(new Member(keycloakUserId, grade));
        memberGradeHistoryRepository.saveAndFlush(new MemberGradeHistory(member, grade, "회원가입 기본 등급 부여"));
        return member;
    }
}
