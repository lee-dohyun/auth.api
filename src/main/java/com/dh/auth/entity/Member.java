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

/** Keycloak identity와 로컬 도메인 데이터(등급, 전화번호 등)를 잇는 회원 앵커. */
@Entity
@Table(name = "members")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "keycloak_user_id", nullable = false, unique = true, length = 36)
    private String keycloakUserId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "current_grade_id", nullable = false)
    private MemberGrade currentGrade;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 계정 본인인증 전화번호(1인 1번호, phone_verifications 이력의 현재값 스냅샷).
     * 배송 연락처처럼 여러 개 가질 수 있는 값이 아니므로 여기 두는 게 맞다 — 주소/배송
     * 연락처가 필요해지면 그건 별도 테이블(예: member_addresses)의 몫.
     */
    @Column(name = "current_phone_number", length = 20)
    private String currentPhoneNumber;

    protected Member() {
    }

    public Member(String keycloakUserId, MemberGrade currentGrade) {
        this.keycloakUserId = keycloakUserId;
        this.currentGrade = currentGrade;
        this.createdAt = LocalDateTime.now();
    }

    public void changeGrade(MemberGrade newGrade) {
        this.currentGrade = newGrade;
    }

    public void changePhoneNumber(String phoneNumber) {
        this.currentPhoneNumber = phoneNumber;
    }

    public Long getId() {
        return id;
    }

    public String getKeycloakUserId() {
        return keycloakUserId;
    }

    public MemberGrade getCurrentGrade() {
        return currentGrade;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getCurrentPhoneNumber() {
        return currentPhoneNumber;
    }
}
