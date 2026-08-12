package com.dh.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.auth.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByKeycloakUserId(String keycloakUserId);
}
