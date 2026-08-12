-- =========================================================
-- 회원 현재 인증 전화번호 (비정규화)
-- phone_verifications는 인증 이력(감사 로그) 용도로 유지하고, members에는
-- "지금 이 계정의 유효한 인증 번호"를 바로 조회할 수 있도록 컬럼을 추가한다.
-- 계정 인증용 번호는 실무 관행상 1인 1번호이므로 UNIQUE로 무결성을 보장한다
-- (배송 연락처처럼 여러 개 가질 수 있는 값이 아님 — members 범위 밖의 별개 개념).
-- =========================================================
ALTER TABLE members
    ADD COLUMN current_phone_number VARCHAR(20) NULL;

CREATE UNIQUE INDEX uq_members_current_phone_number
    ON members (current_phone_number)
    WHERE current_phone_number IS NOT NULL;
