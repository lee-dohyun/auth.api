package com.dh.auth.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 관리자 등급 관리 화면(admin.front)이 쓰는 요청·응답 형태 (gateway#80).
 *
 * <p>등급은 {@code code}, 회원은 {@code keycloakUserId} 로만 가리킨다 — 순번 PK 는 싣지 않는다(캐논 §3).
 */
public final class AdminMemberGradeDtos {

    private AdminMemberGradeDtos() {
    }

    /**
     * 유지 기한의 JSON 형식. ObjectMapper 설정에 맡기면 환경에 따라 {@code [2026,12,31]} 배열로 나갈 수 있어
     * (WRITE_DATES_AS_TIMESTAMPS) 화면이 기대는 형식을 필드에 못박는다.
     */
    private static final String DATE_PATTERN = "yyyy-MM-dd";

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

    /**
     * 등급 고정 상태 (auth.api#49).
     *
     * @param locked 지금 유효한 고정인가. 기한이 지난 고정은 false 다(정기 재산정이 돌기 전이라도)
     * @param until  유지 기한 — 고정이 유효한 마지막 날(KST). null 이면 해제할 때까지. 고정이 아니면 항상 null
     */
    public record GradeLock(boolean locked, @JsonFormat(pattern = DATE_PATTERN) LocalDate until) {
    }

    /** @param history 최신 순 */
    public record MemberGradeDetail(
            String keycloakUserId, CurrentGrade grade, GradeLock lock, List<GradeHistoryItem> history) {
    }

    /**
     * 수동 조정하면 그 등급은 고정된다 — 정기 재산정이 유지 기한까지 이 회원을 건너뛴다.
     *
     * @param reason      필수. 이력에 "수동 조정: {reason}" 으로 남는다
     * @param lockedUntil 유지 기한(그날 포함, KST). null 이면 해제할 때까지 고정. 지난 날짜는 거부된다
     */
    public record GradeAdjustRequest(
            String gradeCode, String reason, @JsonFormat(pattern = DATE_PATTERN) LocalDate lockedUntil) {

        /** 유지 기한 없이(해제할 때까지) 고정하는 조정. */
        public GradeAdjustRequest(String gradeCode, String reason) {
            this(gradeCode, reason, null);
        }
    }

    /**
     * 수동 조정·고정 해제의 응답.
     *
     * @param changed false 면 이미 그 상태라 아무것도 바꾸지 않았다(이력도 늘지 않는다)
     */
    public record GradeAdjustResponse(boolean changed, MemberGradeDetail member) {
    }
}
