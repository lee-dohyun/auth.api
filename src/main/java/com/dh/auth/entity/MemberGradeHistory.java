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

/** 등급 변경 감사 이력. Member.currentGrade는 "현재값" 빠른 조회용, 여기는 변경 추적용. */
@Entity
@Table(name = "member_grade_history")
public class MemberGradeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "grade_id", nullable = false)
    private MemberGrade grade;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Column(length = 100)
    private String reason;

    protected MemberGradeHistory() {
    }

    public MemberGradeHistory(Member member, MemberGrade grade, String reason) {
        this.member = member;
        this.grade = grade;
        this.reason = reason;
        this.assignedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Member getMember() {
        return member;
    }

    public MemberGrade getGrade() {
        return grade;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public String getReason() {
        return reason;
    }
}
