package com.dh.auth.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dh.auth.entity.PhoneVerification;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, Long> {

    List<PhoneVerification> findByMemberId(Long memberId);

    /** 가입 전(member 미연결) 최신 인증 성공 이력 — signup 시점에 "방금 인증했는지" 확인용. */
    Optional<PhoneVerification> findTopByPhoneNumberAndMemberIsNullOrderByVerifiedAtDesc(String phoneNumber);

    /** 인증 성공 이력의 중기 보관 만료분 배치 파기 (실무 관행상 6개월 — 개인정보처리방침에 명시된 값 사용). */
    @Modifying
    @Query("DELETE FROM PhoneVerification p WHERE p.verifiedAt < :cutoff")
    int deleteVerifiedBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 회원 파기 시 그 회원의 인증 이력을 삭제한다.
     *
     * <p>member_id 만 NULL 로 끊지 않고 행을 지우는 이유: 이 테이블이 담고 있는 것은
     * 전화번호 그 자체이므로, 연결만 끊으면 개인정보가 그대로 남는다. 또 member_id 가 NULL 인
     * 행은 "가입 전 인증 이력"으로 해석되어 재가입 판정에 쓰인다({@code findTopBy...MemberIsNull...}).
     */
    int deleteByMemberId(Long memberId);
}
