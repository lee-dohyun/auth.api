-- =========================================================
-- 회원 앵커 테이블
-- Keycloak이 신원(로그인/비밀번호)을 전담하므로, 여기서는 FK만 목적으로
-- keycloak_user_id를 유일키로 두고 로컬 도메인 데이터(등급/전화번호 등)를 매단다.
-- =========================================================
CREATE TABLE members (
    id                BIGSERIAL PRIMARY KEY,
    keycloak_user_id  VARCHAR(36)  NOT NULL,
    current_grade_id  BIGINT       NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_members_keycloak_user_id UNIQUE (keycloak_user_id)
);

-- =========================================================
-- 회원 등급 마스터
-- 코드에 등급을 하드코딩하지 않기 위한 정책 테이블. 등급명/할인율 등은
-- 운영 중 바뀔 수 있으므로 반드시 여기서 관리.
-- =========================================================
CREATE TABLE member_grades (
    id                BIGSERIAL PRIMARY KEY,
    code              VARCHAR(20)   NOT NULL,
    name              VARCHAR(50)   NOT NULL,
    discount_rate     NUMERIC(5,2)  NOT NULL DEFAULT 0,
    min_spend_amount  NUMERIC(12,2) NULL,
    sort_order        INT           NOT NULL,
    is_default        BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT uq_member_grades_code UNIQUE (code)
);

-- 기본 등급은 정확히 하나여야 함 (가입 시 자동 부여될 등급)
CREATE UNIQUE INDEX idx_member_grades_default
    ON member_grades (is_default)
    WHERE is_default = TRUE;

ALTER TABLE members
    ADD CONSTRAINT fk_members_current_grade
    FOREIGN KEY (current_grade_id) REFERENCES member_grades(id);

-- 초기 등급 데이터 (가입 시 기본 부여되는 '일반' 등급 포함)
INSERT INTO member_grades (code, name, discount_rate, sort_order, is_default) VALUES
    ('GENERAL', '일반', 0,    1, TRUE),
    ('SILVER',  '실버', 2.0,  2, FALSE),
    ('GOLD',    '골드', 5.0,  3, FALSE),
    ('VIP',     'VIP',  10.0, 4, FALSE);

-- =========================================================
-- 회원 등급 변경 이력
-- members.current_grade_id는 "현재값" 빠른 조회용, 여기는 감사 추적용.
-- 가입 시 기본 등급 부여도 이 테이블에 한 건 남긴다.
-- =========================================================
CREATE TABLE member_grade_history (
    id           BIGSERIAL PRIMARY KEY,
    member_id    BIGINT       NOT NULL,
    grade_id     BIGINT       NOT NULL,
    assigned_at  TIMESTAMP    NOT NULL DEFAULT now(),
    reason       VARCHAR(100) NULL,
    CONSTRAINT fk_member_grade_history_member FOREIGN KEY (member_id) REFERENCES members(id),
    CONSTRAINT fk_member_grade_history_grade FOREIGN KEY (grade_id) REFERENCES member_grades(id)
);

CREATE INDEX idx_member_grade_history_member_id ON member_grade_history(member_id);

-- =========================================================
-- 휴대폰 OTP 인증 세션 (휘발성)
-- 개인정보 최소보유 원칙: 미인증/만료 건은 단기간(배치로 24~48시간 내) 파기 대상.
-- otp_code는 인증 성공/만료 즉시 NULL 처리.
-- =========================================================
CREATE TABLE phone_otp_attempts (
    id              BIGSERIAL PRIMARY KEY,
    phone_number    VARCHAR(20) NOT NULL,
    otp_code        VARCHAR(6)  NULL,
    otp_expires_at  TIMESTAMP   NOT NULL,
    attempt_count   INT         NOT NULL DEFAULT 0,
    last_sent_at    TIMESTAMP   NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_phone_otp_attempts_phone_number UNIQUE (phone_number)
);

-- =========================================================
-- 휴대폰 인증 성공 이력 (회원과 연결)
-- 가입 전(계정 미생성) 시점엔 member_id가 NULL일 수 있고,
-- 가입 완료 시 UPDATE로 채운다. 부정가입 방지/분쟁 대응 목적으로
-- 실무 관행상 6개월 보관 후 배치 파기 대상 (개인정보처리방침에 명시 필요).
-- =========================================================
CREATE TABLE phone_verifications (
    id            BIGSERIAL PRIMARY KEY,
    member_id     BIGINT      NULL,
    phone_number  VARCHAR(20) NOT NULL,
    verified_at   TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT fk_phone_verifications_member FOREIGN KEY (member_id) REFERENCES members(id)
);

CREATE INDEX idx_phone_verifications_phone_number ON phone_verifications(phone_number);
CREATE INDEX idx_phone_verifications_member_id ON phone_verifications(member_id);
