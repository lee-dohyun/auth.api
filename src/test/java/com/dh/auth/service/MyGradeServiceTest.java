package com.dh.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.dh.auth.config.OrderApiClient;
import com.dh.auth.entity.MemberGrade;
import com.dh.auth.repository.MemberGradeRepository;
import com.dh.auth.repository.MemberRepository;
import com.dh.auth.service.MyGradeService.MyGrade;

/** 고객 화면의 "내 등급" 계산 (gateway#81). */
class MyGradeServiceTest {

    private static final String SUB = "2f1c7a3e-0000-4000-8000-000000000001";

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MemberGradeRepository memberGradeRepository = mock(MemberGradeRepository.class);
    private final OrderApiClient orderApiClient = mock(OrderApiClient.class);
    private final MyGradeService service =
            new MyGradeService(memberRepository, memberGradeRepository, orderApiClient, 6);

    private final MemberGrade general = grade("GENERAL", "0", "0");
    private final MemberGrade silver = grade("SILVER", "2", "300000");
    private final MemberGrade gold = grade("GOLD", "5", "1000000");
    private final MemberGrade vip = grade("VIP", "10", "3000000");

    @BeforeEach
    void setUp() {
        when(memberGradeRepository.findByMinSpendAmountIsNotNullOrderByMinSpendAmountDesc())
                .thenReturn(List.of(vip, gold, silver, general));
    }

    @Test
    @DisplayName("다음 등급은 한 단계 위이고, 남은 금액은 그 기준에서 구매확정액을 뺀 값이다")
    void 다음_등급까지_남은_금액() {
        현재등급(silver);
        구매확정액("450000");

        MyGrade result = service.describe(SUB).orElseThrow();

        assertThat(result.grade().code()).isEqualTo("SILVER");
        assertThat(result.grade().discountRate()).isEqualByComparingTo("2");
        assertThat(result.nextGrade().code()).isEqualTo("GOLD");
        assertThat(result.amountToNextGrade()).isEqualByComparingTo("550000");
        assertThat(result.windowMonths()).isEqualTo(6);
    }

    @Test
    @DisplayName("기준을 이미 넘겼으면 남은 금액은 0 이다 — 산정은 월 1회라 등급은 아직 그대로일 수 있다")
    void 기준을_넘기면_0() {
        현재등급(general);
        구매확정액("350000");

        MyGrade result = service.describe(SUB).orElseThrow();

        assertThat(result.nextGrade().code()).isEqualTo("SILVER");
        assertThat(result.amountToNextGrade()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("최고 등급은 다음 등급도 남은 금액도 없다")
    void 최고_등급() {
        현재등급(vip);
        구매확정액("5000000");

        MyGrade result = service.describe(SUB).orElseThrow();

        assertThat(result.nextGrade()).isNull();
        assertThat(result.amountToNextGrade()).isNull();
    }

    @Test
    @DisplayName("order.api 를 못 부르면 등급은 내려주되 금액은 모른다(null) — 0 으로 대신하지 않는다")
    void 금액을_모르면_null() {
        현재등급(silver);
        when(orderApiClient.fetchConfirmedPurchase(eq(SUB), any()))
                .thenThrow(new OrderApiClient.OrderApiUnavailableException("down"));

        MyGrade result = service.describe(SUB).orElseThrow();

        assertThat(result.grade().code()).isEqualTo("SILVER");
        assertThat(result.confirmedAmount()).isNull();
        assertThat(result.nextGrade().code()).isEqualTo("GOLD");
        assertThat(result.amountToNextGrade()).isNull();
    }

    @Test
    @DisplayName("로컬 회원 행이 없으면 빈 값이다")
    void 회원_행이_없으면_빈값() {
        when(memberRepository.findCurrentGradeByKeycloakUserId(SUB)).thenReturn(Optional.empty());

        assertThat(service.describe(SUB)).isEmpty();
    }

    private void 현재등급(MemberGrade grade) {
        when(memberRepository.findCurrentGradeByKeycloakUserId(SUB)).thenReturn(Optional.of(grade));
    }

    private void 구매확정액(String amount) {
        when(orderApiClient.fetchConfirmedPurchase(eq(SUB), any())).thenReturn(new BigDecimal(amount));
    }

    private static MemberGrade grade(String code, String discountRate, String threshold) {
        try {
            Constructor<MemberGrade> constructor = MemberGrade.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            MemberGrade grade = constructor.newInstance();
            ReflectionTestUtils.setField(grade, "code", code);
            ReflectionTestUtils.setField(grade, "name", code);
            ReflectionTestUtils.setField(grade, "discountRate", new BigDecimal(discountRate));
            ReflectionTestUtils.setField(grade, "minSpendAmount", new BigDecimal(threshold));
            return grade;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
