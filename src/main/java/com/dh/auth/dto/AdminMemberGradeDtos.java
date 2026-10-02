package com.dh.auth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 등급 관리 화면(admin.front)이 쓰는 요청·응답 형태 (gateway#80).
 *
 * <p>등급은 {@code code}, 회원은 {@code keycloakUserId} 로만 가리킨다 — 순번 PK 는 싣지 않는다(캐논 §3).
 */
public final class AdminMemberGradeDtos {

    private AdminMemberGradeDtos() {
    }

    /**
     * 등급 정책 한 줄.
     *
     * @param discountRate   퍼센트 값(5.00 = 5%) — {@code member_grades.discount_rate} 그대로
     * @param minSpendAmount 이 등급이 되기 위한 최소 구매확정액. null 이면 정기 재산정 대상이 아니다
     *                       (수동 조정으로만 부여된다)
     * @param isDefault      가입 시 부여되는 기본 등급인지. 화면에서 바꿀 수 없다
     */
    public record GradePolicy(
            String code,
            String name,
            BigDecimal discountRate,
            BigDecimal minSpendAmount,
            Integer sortOrder,
            boolean isDefault) {
    }

    public record GradePolicyListResponse(List<GradePolicy> items) {
    }

    public record GradePolicyCreateRequest(
            String code,
            String name,
            BigDecimal discountRate,
            BigDecimal minSpendAmount,
            Integer sortOrder) {
    }

    /** 코드는 경로로 받는다. 코드와 기본 등급 여부는 수정 대상이 아니다. */
    public record GradePolicyUpdateRequest(
            String name,
            BigDecimal discountRate,
            BigDecimal minSpendAmount,
            Integer sortOrder) {
    }

    /** @param created false 면 같은 내용의 등급이 이미 있어 아무것도 만들지 않았다는 뜻(재시도) */
    public record GradePolicyCreateResult(GradePolicy policy, boolean created) {
    }

    /** 회원에게 지금 적용되는 등급. */
    public record CurrentGrade(String code, String name, BigDecimal discountRate) {
    }

    /** 등급 이력 한 줄. {@code reason} 이 "수동 조정: " 으로 시작하면 관리자가 직접 바꾼 것이다. */
    public record GradeHistoryItem(String gradeCode, String gradeName, String reason, LocalDateTime assignedAt) {
    }

    /** @param history 최신 순 */
    public record MemberGradeDetail(String keycloakUserId, CurrentGrade grade, List<GradeHistoryItem> history) {
    }

    /** @param reason 필수. 이력에 "수동 조정: {reason}" 으로 남는다 */
    public record GradeAdjustRequest(String gradeCode, String reason) {
    }

    /** @param changed false 면 이미 그 등급이라 아무것도 바꾸지 않았다(이력도 늘지 않는다) */
    public record GradeAdjustResponse(boolean changed, MemberGradeDetail member) {
    }
}
