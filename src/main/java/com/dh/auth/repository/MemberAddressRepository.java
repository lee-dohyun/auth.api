package com.dh.auth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.auth.entity.MemberAddress;

public interface MemberAddressRepository extends JpaRepository<MemberAddress, Long> {

    List<MemberAddress> findByMemberIdOrderByIsDefaultDescCreatedAtDesc(Long memberId);

    Optional<MemberAddress> findByIdAndMemberId(Long id, Long memberId);

    Optional<MemberAddress> findByMemberIdAndIsDefaultTrue(Long memberId);
}
