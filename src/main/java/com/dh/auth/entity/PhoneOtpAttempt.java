package com.dh.auth.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 휴대폰 OTP 발송/검증 세션. 휘발성 데이터 — 미인증/만료 건은 배치로 단기간 내 파기 대상. */
@Entity
@Table(name = "phone_otp_attempts")
public class PhoneOtpAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_number", nullable = false, unique = true, length = 20)
    private String phoneNumber;

    @Column(name = "otp_code", length = 6)
    private String otpCode;

    @Column(name = "otp_expires_at", nullable = false)
    private LocalDateTime otpExpiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_sent_at", nullable = false)
    private LocalDateTime lastSentAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PhoneOtpAttempt() {
    }

    public PhoneOtpAttempt(String phoneNumber, String otpCode, LocalDateTime otpExpiresAt) {
        this.phoneNumber = phoneNumber;
        this.otpCode = otpCode;
        this.otpExpiresAt = otpExpiresAt;
        this.attemptCount = 0;
        this.lastSentAt = LocalDateTime.now();
        this.createdAt = LocalDateTime.now();
    }

    /** 재발송 — 새 row를 만들지 않고 기존 세션을 갱신한다. */
    public void resend(String newOtpCode, LocalDateTime newExpiresAt) {
        this.otpCode = newOtpCode;
        this.otpExpiresAt = newExpiresAt;
        this.lastSentAt = LocalDateTime.now();
        this.attemptCount = 0;
    }

    public void incrementAttempt() {
        this.attemptCount++;
    }

    /** 인증 성공/만료 시 코드 값 자체는 즉시 제거 (탈취/재사용 리스크 최소화). */
    public void clearOtpCode() {
        this.otpCode = null;
    }

    public Long getId() {
        return id;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getOtpCode() {
        return otpCode;
    }

    public LocalDateTime getOtpExpiresAt() {
        return otpExpiresAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public LocalDateTime getLastSentAt() {
        return lastSentAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
