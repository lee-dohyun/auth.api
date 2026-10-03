-- 회원 등급 고정 (auth.api#49).
--
-- 관리자가 수동 조정한 등급을 매월 정기 재산정이 그대로 덮어썼다(CS 보상으로 올린 등급이 최대 한 달만 유지).
-- 수동 조정하면 고정되고, 재산정은 유지 기한까지 그 회원을 건너뛴다.
--
--   grade_locked        수동 조정으로 고정된 상태인가
--   grade_locked_until  고정이 유효한 마지막 날(그날 포함, KST). NULL 이면 해제할 때까지
--
-- 기존 회원은 전부 고정 아님(false)으로 시작한다. 과거의 수동 조정은 이력의 사유 접두어로만 구분돼
-- 지금 등급이 그 조정의 결과인지 알 수 없으므로 소급해서 고정하지 않는다.
ALTER TABLE members ADD COLUMN grade_locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE members ADD COLUMN grade_locked_until DATE;

-- 기한은 고정된 회원에게만 의미가 있다. 고정이 풀렸는데 기한만 남은 행을 막는다.
ALTER TABLE members ADD CONSTRAINT ck_members_grade_lock_until
    CHECK (grade_locked OR grade_locked_until IS NULL);
