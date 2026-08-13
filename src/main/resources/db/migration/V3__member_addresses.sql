-- =========================================================
-- 회원 배송지 주소록
-- 회원 1명이 여러 배송지를 등록/관리할 수 있도록 별도 테이블로 분리한다
-- (members.current_phone_number는 계정 인증용 단일 값이라 배송지와는 성격이 다름).
-- =========================================================
CREATE TABLE member_addresses (
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT       NOT NULL,
    label           VARCHAR(30)  NULL,
    recipient_name  VARCHAR(50)  NOT NULL,
    phone_number    VARCHAR(20)  NOT NULL,
    zip_code        VARCHAR(10)  NOT NULL,
    address1        VARCHAR(200) NOT NULL,
    address2        VARCHAR(200) NULL,
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT fk_member_addresses_member FOREIGN KEY (member_id) REFERENCES members(id)
);

CREATE INDEX idx_member_addresses_member_id ON member_addresses(member_id);

-- 회원당 기본 배송지는 최대 1개.
CREATE UNIQUE INDEX idx_member_addresses_default
    ON member_addresses (member_id)
    WHERE is_default = TRUE;
