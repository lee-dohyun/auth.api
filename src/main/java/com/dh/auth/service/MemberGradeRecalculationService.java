package com.dh.auth.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.auth.config.OrderApiClient;
import com.dh.auth.entity.Member;
import com.dh.auth.entity.MemberGrade;
import com.dh.auth.entity.MemberGradeHistory;
import com.dh.auth.repository.MemberGradeHistoryRepository;
import com.dh.auth.repository.MemberGradeRepository;
import com.dh.auth.repository.MemberRepository;

/**
 * 회원 등급 재산정 (gateway#83).
 *
 * <p>스키마와 4개 등급은 V1 부터 있었지만 <b>산정 로직이 없어 모든 회원이 가입 시 받은
 * GENERAL 에 영원히 머물렀다.</b> 이 서비스가 그 공백을 메운다.
 *
 * <h2>정책 (gateway#85 에서 확정)</h2>
 * <ul>
 *   <li><b>기준</b>: 최근 {@code member-grade.window-months} 개월(기본 6) 구매확정액.
 *       구매확정 = 배송완료이며 환불·취소는 빠진다(order.api 가 그렇게 집계한다)</li>
 *   <li><b>기준 금액</b>: {@code member_grades.min_spend_amount}. 코드가 아니라 테이블에서 읽는다 —
 *       정책은 운영 중 바뀐다</li>
 *   <li><b>주기</b>: 월 1회 배치. 실시간으로 하면 주문·환불마다 등급이 요동치고,
 *       주문 경로에 등급 계산이 얹혀 결제 지연으로 이어진다</li>
 *   <li><b>강등 허용</b>: 산정 결과가 낮으면 내려간다. 내리지 않으면 기간 기준이 무의미해진다</li>
 *   <li><b>소급 없음</b>: 배치가 도는 시점부터 적용된다</li>
 * </ul>
 */
@Service
public class MemberGradeRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(MemberGradeRecalculationService.class);

    private final MemberRepository memberRepository;
    private final MemberGradeRepository memberGradeRepository;
    private final MemberGradeHistoryRepository memberGradeHistoryRepository;
    private final OrderApiClient orderApiClient;
    private final int windowMonths;

    public MemberGradeRecalculationService(
            MemberRepository memberRepository,
            MemberGradeRepository memberGradeRepository,
            MemberGradeHistoryRepository memberGradeHistoryRepository,
            OrderApiClient orderApiClient,
            @Value("${member-grade.window-months:6}") int windowMonths) {
        this.memberRepository = memberRepository;
        this.memberGradeRepository = memberGradeRepository;
        this.memberGradeHistoryRepository = memberGradeHistoryRepository;
        this.orderApiClient = orderApiClient;
        this.windowMonths = windowMonths;
    }

    public record Result(int examined, int changed) {
    }

    /**
     * 전 회원 등급 재산정.
     *
     * <p><b>집계를 먼저 통째로 받아 온 뒤에 등급을 바꾼다.</b> 회원마다 order.api 를 부르면
     * 중간에 실패했을 때 일부만 반영된 상태가 남고, 그 상태에서는 아직 반영되지 않은 회원이
     * 부당하게 옛 등급에 머문다. 호출이 실패하면 아무것도 바꾸지 않고 예외를 낸다.
     *
     * @throws OrderApiClient.OrderApiUnavailableException order.api 집계를 못 받아오면
     */
    @Transactional
    public Result recalculateAll() {
        LocalDateTime since = LocalDateTime.now().minusMonths(windowMonths);
        Map<String, BigDecimal> confirmed = orderApiClient.fetchConfirmedPurchases(since);

        List<MemberGrade> grades = memberGradeRepository.findByMinSpendAmountIsNotNullOrderByMinSpendAmountDesc();
        if (grades.isEmpty()) {
            throw new IllegalStateException("기준 금액이 설정된 등급이 하나도 없습니다. V8 마이그레이션을 확인하세요.");
        }

        List<Member> members = memberRepository.findAll();
        int changed = 0;
        for (Member member : members) {
            // 구매확정이 없는 회원은 응답에 없다 — 0원으로 다뤄야 강등이 동작한다.
            BigDecimal amount = confirmed.getOrDefault(member.getKeycloakUserId(), BigDecimal.ZERO);
            MemberGrade target = resolveGrade(grades, amount);
            if (target == null || target.getId().equals(member.getCurrentGrade().getId())) {
                continue;
            }
            String reason = "%d개월 구매확정액 %s원 기준 재산정 (%s -> %s)".formatted(
                    windowMonths, amount.toPlainString(),
                    member.getCurrentGrade().getCode(), target.getCode());
            member.changeGrade(target);
            memberGradeHistoryRepository.save(new MemberGradeHistory(member, target, reason));
            changed++;
        }

        log.info("회원 등급 재산정 완료. 대상 {}명, 변경 {}명, 기준일 {}", members.size(), changed, since);
        return new Result(members.size(), changed);
    }

    /**
     * 금액 이하 기준을 가진 등급 중 가장 높은 것. {@code grades} 는 기준 금액 내림차순이므로
     * 처음 만나는 것이 정답이다.
     */
    private MemberGrade resolveGrade(List<MemberGrade> grades, BigDecimal amount) {
        for (MemberGrade grade : grades) {
            if (amount.compareTo(grade.getMinSpendAmount()) >= 0) {
                return grade;
            }
        }
        // 기본 등급의 기준이 0원이라 여기 오지 않는 것이 정상이다(V8 CHECK 제약).
        return null;
    }
}
