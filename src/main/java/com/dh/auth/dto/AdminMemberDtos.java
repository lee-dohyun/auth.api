package com.dh.auth.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 관리자 회원 관리 화면(admin.front)이 쓰는 응답 형태. */
public final class AdminMemberDtos {

    private AdminMemberDtos() {
    }

    /**
     * 목록 한 줄.
     *
     * @param keycloakUserId 삭제 시 쓰는 소유자 키(sub). 이메일은 변경 가능하므로 키로 쓰지 않는다.
     * @param linkedToLocal  로컬 members 에 연동된 계정인지. false 면 회원가입이 중간에 실패한
     *                       "좀비" 계정이라는 뜻이며, 관리자가 정리 대상으로 삼아야 한다.
     * @param gradeName      로컬 연동이 없으면 null
     * @param joinedAt       로컬 members.created_at. 없으면 null (Keycloak 생성시각은 별도 필드)
     */
    public record MemberSummary(
            String keycloakUserId,
            String email,
            String name,
            boolean emailVerified,
            boolean enabled,
            boolean linkedToLocal,
            String gradeName,
            LocalDateTime joinedAt,
            LocalDateTime keycloakCreatedAt) {
    }

    public record MemberListResponse(List<MemberSummary> items, int total, int page, int size) {
    }

    /**
     * 삭제 결과. 무엇이 실제로 지워졌는지 그대로 돌려준다 —
     * "성공"만 반환하면 Keycloak 은 지워졌는데 로컬은 아무것도 없었던 경우를 구분할 수 없다.
     */
    public record MemberDeleteResponse(
            String keycloakUserId,
            boolean keycloakDeleted,
            boolean localDataExisted,
            int deletedAddresses,
            int deletedGradeHistories,
            int deletedPhoneVerifications) {
    }
}
