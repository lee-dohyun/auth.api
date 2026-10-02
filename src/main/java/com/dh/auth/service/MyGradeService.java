package com.dh.auth.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dh.auth.config.OrderApiClient;
import com.dh.auth.config.OrderApiClient.OrderApiUnavailableException;
import com.dh.auth.entity.MemberGrade;
import com.dh.auth.repository.MemberGradeRepository;
import com.dh.auth.repository.MemberRepository;

/**
 * 로그인한 회원이 마이페이지에서 보는 자기 등급 (gateway#81).
 *
 * <h2>"다음 등급까지 남은 금액"의 출처</h2>
 * 최근 {@code member-grade.window-months} 개월 구매확정액은 <b>auth.api 에 저장돼 있지 않다.</b>
 * {@code members} 에는 현재 등급 FK 만 있고, {@code member_grade_history.reason} 의 금액은 등급이
 * 바뀐 시점의 문자열일 뿐이다. 그래서 산정 배치({@link MemberGradeRecalculationService})가 쓰는
 * order.api 내부 집계({@link OrderApiClient#fetchConfirmedPurchases})를 <b>같은 기준으로</b> 다시 부른다 —
 * 화면의 숫자와 다음 배치의 판정이 같은 식에서 나와야 한다.
 *
 * <p>그 집계 API 는 회원별 필터가 없어 전 회원 합계를 받아 한 건만 꺼낸다. 회원 수가 늘면
 * order.api 에 회원 단건 조회를 추가해야 한다(이번 범위 밖 — order.api 는 다른 세션이 작업 중).
 *
 * <h2>트랜잭션</h2>
 * 이 클래스에는 {@code @Transactional} 을 붙이지 않는다. 읽기 두 번은 각자 리포지토리 트랜잭션으로
 * 끝나고, 원격 호출은 그 밖에서 일어난다(캐논 §3: 트랜잭션 안에서 원격 HTTP 호출 금지).
 */
@Service
public class MyGradeService {

    private final MemberRepository memberRepository;
    private final MemberGradeRepository memberGradeRepository;
    private final OrderApiClient orderApiClient;
    private final int windowMonths;

    public MyGradeService(
            MemberRepository memberRepository,
            MemberGradeRepository memberGradeRepository,
            OrderApiClient orderApiClient,
            @Value("${member-grade.window-months:6}") int windowMonths) {
        this.memberRepository = memberRepository;
        this.memberGradeRepository = memberGradeRepository;
        this.orderApiClient = orderApiClient;
        this.windowMonths = windowMonths;
    }

    /** {@code discountRate} 는 퍼센트 값이다(5.00 = 5%) — {@code member_grades.discount_rate} 그대로. */
    public record NextGrade(String code, String name, BigDecimal discountRate, BigDecimal minSpendAmount) {
    }

    /**
     * @param nextGrade         바로 위 등급. 최고 등급이거나 현재 등급에 기준 금액이 없으면 null
     * @param amountToNextGrade 다음 등급 기준까지 남은 구매확정액(0 이상). 다음 등급이 없거나
     *                          order.api 집계를 못 받아오면 null — 추정치로 채우지 않는다
     */
    public record MyGrade(
            String code,
            String name,
            BigDecimal discountRate,
            int windowMonths,
            NextGrade nextGrade,
            BigDecimal amountToNextGrade) {
    }

    /** @return 로컬 회원 행이 없는 sub 면 빈 값 */
    public Optional<MyGrade> find(String keycloakUserId) {
        Optional<MemberGrade> current = memberRepository.findCurrentGradeByKeycloakUserId(keycloakUserId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        MemberGrade grade = current.get();
        MemberGrade next = nextGradeOf(grade);
        return Optional.of(new MyGrade(
                grade.getCode(),
                grade.getName(),
                grade.getDiscountRate(),
                windowMonths,
                next == null ? null
                        : new NextGrade(next.getCode(), next.getName(), next.getDiscountRate(), next.getMinSpendAmount()),
                next == null ? null : amountTo(next, keycloakUserId)));
    }

    /**
     * 현재 등급보다 기준 금액이 큰 등급 중 가장 낮은 것. 산정 로직과 같은 목록(기준 금액 내림차순)을
     * 쓰므로 뒤에서부터 처음 만나는 것이 정답이다.
     */
    private MemberGrade nextGradeOf(MemberGrade current) {
        if (current.getMinSpendAmount() == null) {
            // 산정 대상이 아닌 등급 — 어디가 "다음"인지 정의돼 있지 않다.
            return null;
        }
        List<MemberGrade> grades = memberGradeRepository.findByMinSpendAmountIsNotNullOrderByMinSpendAmountDesc();
        for (int i = grades.size() - 1; i >= 0; i--) {
            if (grades.get(i).getMinSpendAmount().compareTo(current.getMinSpendAmount()) > 0) {
                return grades.get(i);
            }
        }
        return null;
    }

    private BigDecimal amountTo(MemberGrade next, String keycloakUserId) {
        BigDecimal confirmed;
        try {
            // 구매확정이 없는 회원은 응답에 없다 — 산정 배치와 똑같이 0원으로 다룬다.
            confirmed = orderApiClient.fetchConfirmedPurchases(LocalDateTime.now().minusMonths(windowMonths))
                    .getOrDefault(keycloakUserId, BigDecimal.ZERO);
        } catch (OrderApiUnavailableException e) {
            // 등급 자체는 보여 줄 수 있다. 금액만 비운다(OrderApiClient 가 이미 ERROR 로그를 남겼다).
            return null;
        }
        // 등급은 월 1회 배치로만 바뀐다. 그 사이에 기준을 넘긴 회원에게 음수를 보여 주지 않는다.
        return next.getMinSpendAmount().subtract(confirmed).max(BigDecimal.ZERO);
    }
}
