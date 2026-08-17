package com.dh.auth.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "agreements")
public class Agreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String type; // e.g. "terms", "privacy"

    @Column(nullable = false)
    private String title;

    @OneToMany(mappedBy = "agreement", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<AgreementArticle> articles = new ArrayList<>();

    protected Agreement() {}

    public Agreement(String type, String title) {
        this.type = type;
        this.title = title;
    }

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public List<AgreementArticle> getArticles() {
        return articles;
    }
}
