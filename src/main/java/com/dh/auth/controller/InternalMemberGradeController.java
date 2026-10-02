package com.dh.auth.controller;

import java.math.BigDecimal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.auth.repository.MemberRepository;
import com.dh.auth.service.MemberGradeRecalculationService;

/**
 * 등급 재산정 수동 트리거. 클러스터 내부망 전용이다 — {@code /internal/**} 은 게이트웨이에
 * 라우트가 없어 외부에서 도달할 수 없다(product.api {@code InternalVariantController} 와 같은 신뢰 경계).
 *
 * <p>월 1회 배치만 있으면 <b>실패했을 때 다음 달까지 손쓸 방법이 없다.</b> 배포 직후 동작 확인도
 * 한 달을 기다려야 한다. 그래서 같은 로직을 부르는 입구를 하나 둔다:
 *
 * <pre>
 * kubectl -n customer exec deploy/auth-api -- \
 *   curl -sS -XPOST localhost:8080/internal/member-grades/recalculate
 * </pre>
 */
@RestController
@RequestMapping("/internal/member-grades")
public class InternalMemberGradeController {

    private final MemberGradeRecalculationService recalculationService;
    private final MemberRepository memberRepository;

    public InternalMemberGradeController(
            MemberGradeRecalculationService recalculationService, MemberRepository memberRepository) {
        this.recalculationService = recalculationService;
        this.memberRepository = memberRepository;
    }

    @PostMapping("/recalculate")
    public MemberGradeRecalculationService.Result recalculate() {
        return recalculationService.recalculateAll();
    }

    /**
     * 회원의 현재 등급과 할인율. order.api 가 주문 금액을 확정할 때 부른다(gateway#82).
     *
     * <p>할인율은 금전적 혜택이라 <b>클라이언트가 보낸 값을 쓸 수 없다</b> — 주문 가격을
     * product.api 에서 확정받는 것과 같은 이유로, 등급은 등급을 가진 쪽(여기)에서만 나온다.
     * 로컬 회원 행이 없는 sub 는 404 다. 호출자가 "할인 없음"으로 처리한다.
     */
    @GetMapping("/members/{keycloakUserId}")
    public ResponseEntity<MemberGradeResponse> currentGrade(@PathVariable String keycloakUserId) {
        return memberRepository.findCurrentGradeByKeycloakUserId(keycloakUserId)
                .map(g -> ResponseEntity.ok(new MemberGradeResponse(g.getCode(), g.getName(), g.getDiscountRate())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** {@code discountRate} 는 퍼센트 값이다(5.00 = 5%) — {@code member_grades.discount_rate} 그대로. */
    public record MemberGradeResponse(String code, String name, BigDecimal discountRate) {
    }
}
