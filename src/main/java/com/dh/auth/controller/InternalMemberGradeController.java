package com.dh.auth.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public InternalMemberGradeController(MemberGradeRecalculationService recalculationService) {
        this.recalculationService = recalculationService;
    }

    @PostMapping("/recalculate")
    public MemberGradeRecalculationService.Result recalculate() {
        return recalculationService.recalculateAll();
    }
}
