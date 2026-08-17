package com.dh.auth.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "agreement_articles")
public class AgreementArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agreement_id", nullable = false)
    private Agreement agreement;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    protected AgreementArticle() {}

    public AgreementArticle(Agreement agreement, String title, String body, Integer sortOrder) {
        this.agreement = agreement;
        this.title = title;
        this.body = body;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Agreement getAgreement() {
        return agreement;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }
}
