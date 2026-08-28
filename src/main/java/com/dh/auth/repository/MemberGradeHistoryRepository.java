package com.dh.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.auth.entity.MemberGradeHistory;

public interface MemberGradeHistoryRepository extends JpaRepository<MemberGradeHistory, Long> {

    /**
     * 회원 파기 시 등급 이력을 함께 삭제한다. 이력 자체는 개인정보가 아니지만
     * {@code member_id} 가 NOT NULL FK 라 회원 행보다 먼저 지워야 한다.
     */
    int deleteByMemberId(Long memberId);
}
