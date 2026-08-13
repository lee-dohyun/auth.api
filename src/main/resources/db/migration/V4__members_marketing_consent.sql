-- =========================================================
-- 마케팅 정보 수신 동의 (선택 동의)
-- 회원가입 화면의 "(선택) 마케팅 정보 수신 동의" 체크박스가 저장되는 곳.
-- 정보통신망법상 동의 사실을 입증할 수 있어야 하므로 여부만이 아니라
-- 동의/철회 시각도 같이 남긴다. 기존 회원은 동의한 적이 없으므로 FALSE.
-- 마이페이지에서 수신동의를 켜고 끄는 기능이 생기면 그때 변경 이력 테이블
-- (member_grade_history 같은 패턴)을 별도로 두는 걸 검토할 것 — 지금은 가입
-- 시점의 단일 기록만 필요해서 members에 스냅샷으로 둔다.
-- =========================================================
ALTER TABLE members
    ADD COLUMN marketing_opt_in     BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN marketing_opt_in_at  TIMESTAMP NULL,
    ADD COLUMN marketing_opt_out_at TIMESTAMP NULL;
