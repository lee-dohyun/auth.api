package com.dh.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dh.auth.entity.Member;
import com.dh.auth.entity.MemberGrade;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByKeycloakUserId(String keycloakUserId);

    boolean existsByCurrentPhoneNumber(String currentPhoneNumber);

    /**
     * 회원의 현재 등급만 바로 읽는다. {@code currentGrade} 가 LAZY 라 {@link #findByKeycloakUserId}
     * 로 받은 뒤 트랜잭션 밖(컨트롤러)에서 등급을 건드리면 LazyInitializationException 이 난다
     * ({@code open-in-view: false}).
     */
    @Query("select m.currentGrade from Member m where m.keycloakUserId = :keycloakUserId")
    Optional<MemberGrade> findCurrentGradeByKeycloakUserId(@Param("keycloakUserId") String keycloakUserId);

}
