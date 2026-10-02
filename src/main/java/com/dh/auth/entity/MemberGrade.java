package com.dh.auth.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "member_grades")
public class MemberGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "discount_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountRate;

    @Column(name = "min_spend_amount", precision = 12, scale = 2)
    private BigDecimal minSpendAmount;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected MemberGrade() {
    }

    /**
     * 관리자 화면에서 등급을 새로 만들 때 쓴다(gateway#80). 기본 등급 여부는 받지 않는다 —
     * 기본 등급은 정확히 하나여야 하고(V1 부분 유니크 인덱스) 가입 경로가 그것에 기대므로
     * 마이그레이션으로만 정한다.
     */
    public MemberGrade(String code, String name, BigDecimal discountRate, BigDecimal minSpendAmount,
            Integer sortOrder) {
        this.code = code;
        this.name = name;
        this.discountRate = discountRate;
        this.minSpendAmount = minSpendAmount;
        this.sortOrder = sortOrder;
        this.isDefault = false;
        this.createdAt = LocalDateTime.now();
    }

    /** 등급 정책(이름·혜택·기준) 변경. 코드와 기본 등급 여부는 바꾸지 않는다. */
    public void changePolicy(String name, BigDecimal discountRate, BigDecimal minSpendAmount, Integer sortOrder) {
        this.name = name;
        this.discountRate = discountRate;
        this.minSpendAmount = minSpendAmount;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getDiscountRate() {
        return discountRate;
    }

    public BigDecimal getMinSpendAmount() {
        return minSpendAmount;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
