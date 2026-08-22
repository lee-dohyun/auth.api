-- 등급 산정 기준 금액 확정 (gateway#85 "요구사항/정책 확정")
--
-- V1 이 member_grades 테이블과 4개 등급(GENERAL/SILVER/GOLD/VIP)을 시드하면서 min_spend_amount
-- 컬럼까지 만들어 뒀지만 값은 전부 NULL 이었다. 즉 "등급을 무엇으로 나눌 것인가"만 정해져 있고
-- "얼마부터인가"가 비어 있어서, 스키마는 있는데 아무도 등급이 오르지 않는 상태로 1년 가까이 있었다.
--
-- 기준: 최근 6개월 구매확정액(배송완료 기준, 환불/취소 제외).
-- 이 값들은 정책이지 상수가 아니다 — 운영 중 바뀔 것을 전제로 코드가 아니라 이 테이블에서 읽는다.
-- (V1 주석: "코드에 등급을 하드코딩하지 않기 위한 정책 테이블")

UPDATE member_grades SET min_spend_amount = 0        WHERE code = 'GENERAL';
UPDATE member_grades SET min_spend_amount = 300000   WHERE code = 'SILVER';
UPDATE member_grades SET min_spend_amount = 1000000  WHERE code = 'GOLD';
UPDATE member_grades SET min_spend_amount = 3000000  WHERE code = 'VIP';

-- 기본 등급은 "구매 이력이 없어도 부여되는 등급"이므로 기준 금액이 반드시 있어야 한다(0원).
-- 여기가 NULL 이면 산정 로직이 신규 회원을 어느 등급에도 못 넣는다.
--
-- 위 UPDATE 는 code 로 찾는다. 코드가 바뀐 환경에서는 한 건도 안 맞을 수 있고, 그 상태로
-- 아래 CHECK 를 걸면 마이그레이션이 실패해 애플리케이션이 아예 안 뜬다. 등급이 안 오르는 것보다
-- 서비스가 죽는 게 훨씬 나쁘므로, 기본 등급만은 code 와 무관하게 채워 넣는다.
UPDATE member_grades SET min_spend_amount = 0 WHERE is_default = TRUE AND min_spend_amount IS NULL;

ALTER TABLE member_grades
    ADD CONSTRAINT chk_member_grades_default_has_threshold
    CHECK (is_default = FALSE OR min_spend_amount IS NOT NULL);

COMMENT ON COLUMN member_grades.min_spend_amount IS
    '이 등급이 되기 위한 최소 누적 구매확정액. 산정 기간은 코드가 정한다(현재 최근 6개월). NULL 이면 산정 대상에서 제외된다.';
