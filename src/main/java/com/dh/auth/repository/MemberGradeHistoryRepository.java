package com.dh.auth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dh.auth.entity.MemberGradeHistory;

public interface MemberGradeHistoryRepository extends JpaRepository<MemberGradeHistory, Long> {

    /**
     * 회원 파기 시 등급 이력을 함께 삭제한다. 이력 자체는 개인정보가 아니지만
     * {@code member_id} 가 NOT NULL FK 라 회원 행보다 먼저 지워야 한다.
     */
    int deleteByMemberId(Long memberId);

    /**
     * 회원의 등급 이력을 최신 순으로. 등급을 함께 가져온다 — {@code open-in-view: false} 라
     * 트랜잭션 밖에서 LAZY 등급을 건드리면 LazyInitializationException 이 난다(gateway#80).
     */
    @Query("select h from MemberGradeHistory h join fetch h.grade "
            + "where h.member.id = :memberId order by h.assignedAt desc, h.id desc")
    List<MemberGradeHistory> findWithGradeByMemberId(@Param("memberId") Long memberId);
}
