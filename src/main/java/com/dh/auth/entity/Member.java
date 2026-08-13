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

    /**
     * 마케팅 정보 수신 동의 여부(선택 동의). 동의/철회 시각을 같이 남기는 건
     * 정보통신망법상 동의 사실을 입증할 수 있어야 하기 때문이다.
     */
    @Column(name = "marketing_opt_in", nullable = false)
    private boolean marketingOptIn;

    @Column(name = "marketing_opt_in_at")
    private LocalDateTime marketingOptInAt;

    @Column(name = "marketing_opt_out_at")
    private LocalDateTime marketingOptOutAt;

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

    /**
     * 수신 동의를 변경한다. 같은 값으로 다시 호출해도 시각을 덮어쓰지 않는다 —
     * 동의/철회 시각은 "언제 그 상태가 됐는지"를 뜻해야 하므로.
     */
    public void changeMarketingOptIn(boolean optIn) {
        if (this.marketingOptIn == optIn) {
            return;
        }
        this.marketingOptIn = optIn;
        if (optIn) {
            this.marketingOptInAt = LocalDateTime.now();
        } else {
            this.marketingOptOutAt = LocalDateTime.now();
        }
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

    public boolean isMarketingOptIn() {
        return marketingOptIn;
    }

    public LocalDateTime getMarketingOptInAt() {
        return marketingOptInAt;
    }

    public LocalDateTime getMarketingOptOutAt() {
        return marketingOptOutAt;
    }
}
