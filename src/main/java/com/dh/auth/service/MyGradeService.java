package com.dh.auth.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dh.auth.config.OrderApiClient;
import com.dh.auth.entity.MemberGrade;
import com.dh.auth.repository.MemberGradeRepository;
import com.dh.auth.repository.MemberRepository;

/**
 * 고객 화면에 보여 줄 "내 등급" (gateway#81).
 *
 * <p>현재 등급·할인율은 주문 할인(order.api 가 {@code /internal/member-grades/members/{sub}} 로 읽는 값)과
 * 같은 행에서 나온다 — 화면에 보이는 할인율과 실제 적용되는 할인율이 어긋나지 않게 하기 위해서다.
 *
 * <p>"다음 등급까지 남은 금액"은 등급 산정 배치와 같은 기준(최근 {@code member-grade.window-months}
 * 개월 구매확정액)으로 계산한다. order.api 를 못 부르면 그 금액은 <b>모른다</b>(null) — 0 원으로
 * 대신하면 틀린 안내가 된다. 원격 호출을 하므로 이 클래스에는 트랜잭션을 걸지 않는다.
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

    /** {@code discountRate} 는 퍼센트 값(5.00 = 5%). */
    public record GradeInfo(String code, String name, BigDecimal discountRate, BigDecimal minSpendAmount) {
        static GradeInfo from(MemberGrade grade) {
            return new GradeInfo(grade.getCode(), grade.getName(), grade.getDiscountRate(), grade.getMinSpendAmount());
        }
    }

    /**
     * @param confirmedAmount   산정 기간 구매확정액. order.api 조회 실패 시 null
     * @param nextGrade         한 단계 위 등급. 이미 최고 등급이면 null
     * @param amountToNextGrade 다음 등급 기준까지 남은 금액(0 이상). 최고 등급이거나 금액을 모르면 null.
     *                          0 이면 기준은 채웠고 다음 월 산정 때 오른다는 뜻이다(산정은 월 1회)
     */
    public record MyGrade(GradeInfo grade, int windowMonths, BigDecimal confirmedAmount,
            GradeInfo nextGrade, BigDecimal amountToNextGrade) {
    }

    /** 로컬 회원 행이 없는 sub 는 빈 값이다 — 그런 계정은 주문에서도 등급 할인을 받지 않는다. */
    public Optional<MyGrade> describe(String keycloakUserId) {
        Optional<MemberGrade> current = memberRepository.findCurrentGradeByKeycloakUserId(keycloakUserId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        MemberGrade grade = current.get();
        MemberGrade next = nextGradeOf(grade);

        BigDecimal confirmed = null;
        try {
            confirmed = orderApiClient.fetchConfirmedPurchase(
                    keycloakUserId, LocalDateTime.now().minusMonths(windowMonths));
        } catch (OrderApiClient.OrderApiUnavailableException e) {
            // 금액을 모르는 채로 등급만 내려준다. 로그는 클라이언트가 남겼다.
        }

        BigDecimal remaining = null;
        if (next != null && confirmed != null) {
            remaining = next.getMinSpendAmount().subtract(confirmed).max(BigDecimal.ZERO);
        }
        return Optional.of(new MyGrade(GradeInfo.from(grade), windowMonths, confirmed,
                next == null ? null : GradeInfo.from(next), remaining));
    }

    /** 기준 금액이 현재 등급보다 큰 등급 중 가장 낮은 것. 기준 금액이 없는 등급은 자동 산정 대상이 아니라 건너뛴다. */
    private MemberGrade nextGradeOf(MemberGrade current) {
        List<MemberGrade> descending = memberGradeRepository.findByMinSpendAmountIsNotNullOrderByMinSpendAmountDesc();
        BigDecimal currentThreshold = current.getMinSpendAmount() == null ? BigDecimal.ZERO : current.getMinSpendAmount();
        MemberGrade next = null;
        for (MemberGrade candidate : descending) {
            if (candidate.getMinSpendAmount().compareTo(currentThreshold) > 0) {
                next = candidate;
            }
        }
        return next;
    }
}
