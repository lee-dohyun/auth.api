package com.dh.auth.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 휴대폰 인증 성공 이력. 가입 전(계정 미생성) 시점엔 member가 null일 수 있고,
 * 가입 완료 시 채운다. 부정가입 방지/분쟁 대응 목적으로 중기 보관 후 배치 파기 대상.
 */
@Entity
@Table(name = "phone_verifications")
public class PhoneVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "verified_at", nullable = false)
    private LocalDateTime verifiedAt;

    protected PhoneVerification() {
    }

    public PhoneVerification(Member member, String phoneNumber) {
        this.member = member;
        this.phoneNumber = phoneNumber;
        this.verifiedAt = LocalDateTime.now();
    }

    public void linkMember(Member member) {
        this.member = member;
    }

    public Long getId() {
        return id;
    }

    public Member getMember() {
        return member;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }
}
