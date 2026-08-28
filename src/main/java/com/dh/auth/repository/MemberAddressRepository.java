package com.dh.auth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.auth.entity.MemberAddress;

public interface MemberAddressRepository extends JpaRepository<MemberAddress, Long> {

    List<MemberAddress> findByMemberIdOrderByIsDefaultDescCreatedAtDesc(Long memberId);

    Optional<MemberAddress> findByIdAndMemberId(Long id, Long memberId);

    Optional<MemberAddress> findByMemberIdAndIsDefaultTrue(Long memberId);

    /** 회원 파기 시 배송지(수령인명·연락처·주소)를 함께 파기한다. */
    int deleteByMemberId(Long memberId);
}
