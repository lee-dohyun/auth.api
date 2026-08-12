package com.dh.auth.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dh.auth.entity.PhoneOtpAttempt;

public interface PhoneOtpAttemptRepository extends JpaRepository<PhoneOtpAttempt, Long> {

    Optional<PhoneOtpAttempt> findByPhoneNumber(String phoneNumber);

    /** 개인정보 최소보유 원칙 — 미인증 채로 만료된 지 오래된 세션은 배치로 파기. */
    @Modifying
    @Query("DELETE FROM PhoneOtpAttempt p WHERE p.otpExpiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
