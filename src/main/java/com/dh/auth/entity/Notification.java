package com.dh.auth.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 회원 알림함의 알림 한 건(gateway#180). 문구는 발생시킨 쪽이 완성해서 넘긴 것을 그대로 저장한다 —
 * 읽는 시점에 다시 조립하지 않으므로, 주문이 나중에 바뀌어도 "그때 보낸 알림"은 그대로 남는다.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 응답·URL 에 노출하는 식별자. 순번 PK 는 밖으로 내보내지 않는다. */
    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 알림 종류(예: ORDER_PAID). 헤더가 아이콘·분류에 쓴다. 값 목록은 gateway Wiki Glossary 기준. */
    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String body;

    @Column(name = "link_url", length = 500)
    private String linkUrl;

    /** 발생시키는 쪽이 정한 멱등성 키. (member_id, dedup_key) UNIQUE. */
    @Column(name = "dedup_key", nullable = false, length = 120)
    private String dedupKey;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Notification() {
    }

    public Notification(Long memberId, String type, String title, String body, String linkUrl, String dedupKey) {
        this.publicId = UUID.randomUUID();
        this.memberId = memberId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.linkUrl = linkUrl;
        this.dedupKey = dedupKey;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getLinkUrl() {
        return linkUrl;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
