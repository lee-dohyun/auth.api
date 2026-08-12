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
}
