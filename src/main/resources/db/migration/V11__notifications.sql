-- =========================================================
-- 회원 알림함 (gateway#180, posselect-shell#79)
--
-- 알림을 "발생시키는 쪽"(order.api 등)이 내부 API 로 넣고, 공통 헤더가 회원 본인 것을 읽는다.
-- 회원 도메인(이 DB)에 두는 이유: 알림함은 회원에 딸린 데이터라 탈퇴 시 같이 파기돼야 하고,
-- 발생 주체가 여러 서비스로 늘어도 읽는 쪽은 한 곳이면 된다.
--
-- dedup_key: 발생시키는 쪽이 정하는 멱등성 키(예: order-paid:123). 재시도·중복 호출이
--            같은 알림을 두 번 만들지 않게 회원 단위 UNIQUE 로 막는다(캐논: 쓰기 API 는 멱등).
-- public_id: URL/응답에 노출하는 식별자. 순번 PK 는 밖으로 내보내지 않는다(캐논 §보안).
-- ON DELETE CASCADE: 회원 파기(MemberPurgeService) 때 알림도 같이 사라진다 — 본문에 주문 금액 등
--            개인 거래 정보가 들어가므로 회원 행보다 오래 남으면 안 된다.
-- =========================================================
CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    public_id   UUID          NOT NULL,
    member_id   BIGINT        NOT NULL,
    type        VARCHAR(40)   NOT NULL,
    title       VARCHAR(200)  NOT NULL,
    body        VARCHAR(1000) NULL,
    link_url    VARCHAR(500)  NULL,
    dedup_key   VARCHAR(120)  NOT NULL,
    read_at     TIMESTAMP     NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT fk_notifications_member FOREIGN KEY (member_id) REFERENCES members(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_notifications_public_id ON notifications (public_id);
CREATE UNIQUE INDEX uq_notifications_member_dedup ON notifications (member_id, dedup_key);
-- 알림함 목록(최신순)과 안 읽은 개수 조회용.
CREATE INDEX idx_notifications_member_created ON notifications (member_id, created_at DESC);
