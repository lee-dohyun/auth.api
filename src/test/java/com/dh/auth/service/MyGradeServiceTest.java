package com.dh.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.dh.auth.entity.MemberGrade;
import com.dh.auth.repository.MemberGradeRepository;
import com.dh.auth.repository.MemberRepository;
import com.dh.auth.service.MyGradeService.MyGrade;

/**
 * 마이페이지 등급 조회 규칙 고정 (gateway#81).
 *
 * <p>"다음 등급까지 남은 금액"은 고객이 구매 결정을 내리는 근거가 되는 숫자다. 경계(이미 넘김,
 * 최고 등급, 집계 실패)에서 틀린 숫자를 보여 주느니 숫자를 안 보여 주는 쪽으로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyGradeServiceTest {

    private static final String SUB = "sub-1";

    @Mock private MemberRepository memberRepository;
    @Mock private MemberGradeRepository memberGradeRepository;
    @Mock private OrderApiClient orderApiClient;

    private MyGradeService service;

    private MemberGrade general;
    private MemberGrade silver;
    private MemberGrade gold;
    private MemberGrade vip;

    /** MemberGrade 는 기본 생성자가 protected 다(JPA 전용) — MemberGradeRecalculationServiceTest 와 같은 우회. */
    private static MemberGrade grade(long id, String code, String name, String rate, String threshold) {
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
        ReflectionTestUtils.setField(g, "name", name);
        ReflectionTestUtils.setField(g, "discountRate", new BigDecimal(rate));
        ReflectionTestUtils.setField(g, "minSpendAmount", threshold == null ? null : new BigDecimal(threshold));
        ReflectionTestUtils.setField(g, "sortOrder", (int) id);
        return g;
    }

    @BeforeEach
    void setUp() {
        general = grade(1, "GENERAL", "일반", "0.00", "0");
        silver = grade(2, "SILVER", "실버", "2.00", "300000");
        gold = grade(3, "GOLD", "골드", "5.00", "1000000");
        vip = grade(4, "VIP", "VIP", "10.00", "3000000");
        when(memberGradeRepository.findByMinSpendAmountIsNotNullOrderByMinSpendAmountDesc())
                .thenReturn(List.of(vip, gold, silver, general));
        service = new MyGradeService(memberRepository, memberGradeRepository, orderApiClient, 6);
    }

    private void currentGrade(MemberGrade grade) {
        when(memberRepository.findCurrentGradeByKeycloakUserId(SUB)).thenReturn(Optional.of(grade));
    }

    private void confirmed(Map<String, BigDecimal> amounts) {
        // 구매확정이 없는 회원은 order.api 단건 조회가 0 을 준다.
        when(orderApiClient.fetchConfirmedPurchase(eq(SUB), any()))
                .thenReturn(amounts.getOrDefault(SUB, BigDecimal.ZERO));
    }

    @Test
    @DisplayName("로컬 회원 행이 없으면 빈 값이다")
    void 회원이_없으면_빈값() {
        when(memberRepository.findCurrentGradeByKeycloakUserId(SUB)).thenReturn(Optional.empty());

        assertThat(service.find(SUB)).isEmpty();
        verify(orderApiClient, never()).fetchConfirmedPurchase(any(), any());
    }

    @Test
    @DisplayName("현재 등급·할인율과 바로 위 등급, 남은 금액을 돌려준다")
    void 다음_등급까지_남은_금액() {
        currentGrade(silver);
        confirmed(Map.of(SUB, new BigDecimal("450000.00"), "other", new BigDecimal("9999999")));

        MyGrade result = service.find(SUB).orElseThrow();

        assertThat(result.code()).isEqualTo("SILVER");
        assertThat(result.name()).isEqualTo("실버");
        assertThat(result.discountRate()).isEqualByComparingTo("2.00");
        assertThat(result.windowMonths()).isEqualTo(6);
        assertThat(result.nextGrade().code()).isEqualTo("GOLD");
        assertThat(result.nextGrade().name()).isEqualTo("골드");
        assertThat(result.nextGrade().discountRate()).isEqualByComparingTo("5.00");
        assertThat(result.nextGrade().minSpendAmount()).isEqualByComparingTo("1000000");
        assertThat(result.amountToNextGrade()).isEqualByComparingTo("550000");
    }

    @Test
    @DisplayName("구매확정이 없는 회원은 응답에 없으므로 0원으로 계산한다")
    void 구매확정이_없으면_0원() {
        currentGrade(general);
        confirmed(Map.of());

        MyGrade result = service.find(SUB).orElseThrow();

        assertThat(result.nextGrade().code()).isEqualTo("SILVER");
        assertThat(result.amountToNextGrade()).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("이미 기준을 넘겼어도(월 배치 전) 남은 금액은 음수가 아니라 0이다")
    void 기준을_넘겼으면_0() {
        currentGrade(general);
        confirmed(Map.of(SUB, new BigDecimal("350000")));

        assertThat(service.find(SUB).orElseThrow().amountToNextGrade()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("최고 등급이면 다음 등급도 남은 금액도 없고 order.api 를 부르지 않는다")
    void 최고_등급() {
        currentGrade(vip);

        MyGrade result = service.find(SUB).orElseThrow();

        assertThat(result.code()).isEqualTo("VIP");
        assertThat(result.nextGrade()).isNull();
        assertThat(result.amountToNextGrade()).isNull();
        verify(orderApiClient, never()).fetchConfirmedPurchase(any(), any());
    }

    @Test
    @DisplayName("order.api 집계가 실패하면 등급은 보여 주되 남은 금액만 null 이다")
    void 집계_실패() {
        currentGrade(silver);
        when(orderApiClient.fetchConfirmedPurchase(eq(SUB), any()))
                .thenThrow(new OrderApiUnavailableException("down"));

        MyGrade result = service.find(SUB).orElseThrow();

        assertThat(result.code()).isEqualTo("SILVER");
        assertThat(result.nextGrade().code()).isEqualTo("GOLD");
        assertThat(result.amountToNextGrade()).isNull();
    }

    @Test
    @DisplayName("현재 등급에 기준 금액이 없으면(산정 대상 밖) 다음 등급을 지어내지 않는다")
    void 기준_없는_등급() {
        currentGrade(grade(9, "STAFF", "임직원", "20.00", null));

        MyGrade result = service.find(SUB).orElseThrow();

        assertThat(result.nextGrade()).isNull();
        assertThat(result.amountToNextGrade()).isNull();
        verify(orderApiClient, never()).fetchConfirmedPurchase(any(), any());
    }
}
