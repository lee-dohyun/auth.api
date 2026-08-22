package com.dh.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.dh.auth.config.OrderApiClient;
import com.dh.auth.config.OrderApiClient.OrderApiUnavailableException;
import com.dh.auth.entity.Member;
import com.dh.auth.entity.MemberGrade;
import com.dh.auth.entity.MemberGradeHistory;
import com.dh.auth.repository.MemberGradeHistoryRepository;
import com.dh.auth.repository.MemberGradeRepository;
import com.dh.auth.repository.MemberRepository;

/**
 * 등급 산정 규칙 고정 (gateway#83).
 *
 * <p>등급은 할인율로 이어지는 <b>금전적 혜택</b>이라 경계값이 틀리면 그대로 손실이거나 불공정이다.
 * 특히 "기준 금액과 정확히 같을 때"와 "강등"은 눈으로 확인되지 않는 영역이라 여기서 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberGradeRecalculationServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private MemberGradeRepository memberGradeRepository;
    @Mock private MemberGradeHistoryRepository memberGradeHistoryRepository;
    @Mock private OrderApiClient orderApiClient;

    private MemberGradeRecalculationService service;

    private MemberGrade general;
    private MemberGrade silver;
    private MemberGrade gold;
    private MemberGrade vip;

    /**
     * MemberGrade 는 기본 생성자가 protected 다(JPA 전용). 등급 마스터는 마이그레이션으로만
     * 만들어지고 애플리케이션이 새로 만들지 않기 때문인데, 테스트에서는 그 제약을 우회해야 한다.
     */
    private static MemberGrade grade(long id, String code, String threshold) {
        MemberGrade g;
        try {
            var ctor = MemberGrade.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            g = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ReflectionTestUtils.setField(g, "id", id);
        ReflectionTestUtils.setField(g, "code", code);
        ReflectionTestUtils.setField(g, "name", code);
        ReflectionTestUtils.setField(g, "discountRate", BigDecimal.ZERO);
        ReflectionTestUtils.setField(g, "minSpendAmount", new BigDecimal(threshold));
        ReflectionTestUtils.setField(g, "sortOrder", (int) id);
        return g;
    }

    @BeforeEach
    void setUp() {
        general = grade(1, "GENERAL", "0");
        silver = grade(2, "SILVER", "300000");
        gold = grade(3, "GOLD", "1000000");
        vip = grade(4, "VIP", "3000000");
        // 서비스는 기준 금액 내림차순을 전제한다 — 정렬이 곧 규칙이다.
        when(memberGradeRepository.findByMinSpendAmountIsNotNullOrderByMinSpendAmountDesc())
                .thenReturn(List.of(vip, gold, silver, general));
        service = new MemberGradeRecalculationService(
                memberRepository, memberGradeRepository, memberGradeHistoryRepository, orderApiClient, 6);
    }

    private Member member(String sub, MemberGrade current) {
        return new Member(sub, current);
    }

    private void givenPurchases(Map<String, BigDecimal> amounts) {
        when(orderApiClient.fetchConfirmedPurchases(any(LocalDateTime.class))).thenReturn(amounts);
    }

    @Test
    @DisplayName("구매확정액에 맞는 등급으로 올라간다")
    void promotes() {
        Member m = member("user-1", general);
        when(memberRepository.findAll()).thenReturn(List.of(m));
        givenPurchases(Map.of("user-1", new BigDecimal("1500000")));

        var result = service.recalculateAll();

        assertThat(m.getCurrentGrade().getCode()).isEqualTo("GOLD");
        assertThat(result.changed()).isEqualTo(1);
        verify(memberGradeHistoryRepository).save(any(MemberGradeHistory.class));
    }

    @Test
    @DisplayName("기준 금액과 정확히 같으면 그 등급이다 — 경계는 이상(>=)이다")
    void thresholdIsInclusive() {
        Member m = member("user-1", general);
        when(memberRepository.findAll()).thenReturn(List.of(m));
        givenPurchases(Map.of("user-1", new BigDecimal("300000")));

        service.recalculateAll();

        assertThat(m.getCurrentGrade().getCode()).isEqualTo("SILVER");
    }

    @Test
    @DisplayName("1원 모자라면 아래 등급이다")
    void justBelowThreshold() {
        Member m = member("user-1", general);
        when(memberRepository.findAll()).thenReturn(List.of(m));
        givenPurchases(Map.of("user-1", new BigDecimal("299999")));

        service.recalculateAll();

        assertThat(m.getCurrentGrade().getCode()).isEqualTo("GENERAL");
    }

    @Test
    @DisplayName("기간 내 구매확정이 없으면 강등된다 — 응답에 없는 회원은 0원이다")
    void demotesWhenNoRecentPurchase() {
        Member m = member("user-1", vip);
        when(memberRepository.findAll()).thenReturn(List.of(m));
        givenPurchases(Map.of());

        var result = service.recalculateAll();

        assertThat(m.getCurrentGrade().getCode()).isEqualTo("GENERAL");
        assertThat(result.changed()).isEqualTo(1);
    }

    @Test
    @DisplayName("등급이 그대로면 이력을 남기지 않는다 — 매달 같은 이력이 쌓이면 이력이 쓸모없어진다")
    void unchangedGradeWritesNoHistory() {
        Member m = member("user-1", silver);
        when(memberRepository.findAll()).thenReturn(List.of(m));
        givenPurchases(Map.of("user-1", new BigDecimal("500000")));

        var result = service.recalculateAll();

        assertThat(result.changed()).isZero();
        verify(memberGradeHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("order.api 집계에 실패하면 아무 등급도 바꾸지 않는다 — 부분 반영은 부당한 강등을 만든다")
    void failedAggregationChangesNothing() {
        Member m = member("user-1", vip);
        when(memberRepository.findAll()).thenReturn(List.of(m));
        when(orderApiClient.fetchConfirmedPurchases(any(LocalDateTime.class)))
                .thenThrow(new OrderApiUnavailableException("boom"));

        assertThatThrownBy(() -> service.recalculateAll())
                .isInstanceOf(OrderApiUnavailableException.class);

        assertThat(m.getCurrentGrade().getCode()).isEqualTo("VIP");
        verify(memberGradeHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("기준 금액이 설정된 등급이 하나도 없으면 즉시 실패한다 — 전원 강등을 막는다")
    void noConfiguredGradesFailsFast() {
        when(memberGradeRepository.findByMinSpendAmountIsNotNullOrderByMinSpendAmountDesc())
                .thenReturn(List.of());
        givenPurchases(Map.of());

        assertThatThrownBy(() -> service.recalculateAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V8");
    }
}
