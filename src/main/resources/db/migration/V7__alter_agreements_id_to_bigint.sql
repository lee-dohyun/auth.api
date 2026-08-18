-- =========================================================
-- V6 스크립트가 이미 배포된 상태에서 엔티티 타입을 Long으로 맞추기 위해
-- V6 스크립트를 변경하지 않고 V7 신규 마이그레이션을 추가하여 BIGINT로 전환
-- =========================================================

ALTER TABLE agreements ALTER COLUMN id TYPE BIGINT;
ALTER TABLE agreement_articles ALTER COLUMN id TYPE BIGINT;
