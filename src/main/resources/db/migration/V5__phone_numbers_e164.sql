-- =========================================================
-- 전화번호 저장 형식을 E.164(+국가번호) 정규형으로 고정 (Redmine posselect #153)
--
-- 기존엔 하이픈만 제거한 국내 표기(01012345678)를 저장했다. 해외 사용자 가입을 열려면
-- 국가번호가 필요하고, 무엇보다 같은 번호가 표기에 따라 여러 값으로 저장되면
-- UNIQUE 제약과 인증 이력 조회가 모두 새기 때문에 정규형을 DB에서도 강제한다.
--
-- 백필이 없는 이유: 이 마이그레이션 시점에 members / phone_verifications /
-- phone_otp_attempts 전부 0건이었다(휴대폰 인증 자체가 2026-08-12 도입, 실사용 전).
-- 데이터가 쌓인 뒤였다면 국내번호 → +82 변환 백필이 먼저 필요했을 것.
--
-- 길이: E.164는 최대 15자리 + '+' = 16자라 기존 VARCHAR(20)로 충분해서 컬럼은 그대로 둔다.
-- =========================================================

ALTER TABLE members
    ADD CONSTRAINT ck_members_current_phone_number_e164
    CHECK (current_phone_number ~ '^\+[1-9][0-9]{1,14}$');

ALTER TABLE phone_otp_attempts
    ADD CONSTRAINT ck_phone_otp_attempts_phone_number_e164
    CHECK (phone_number ~ '^\+[1-9][0-9]{1,14}$');

ALTER TABLE phone_verifications
    ADD CONSTRAINT ck_phone_verifications_phone_number_e164
    CHECK (phone_number ~ '^\+[1-9][0-9]{1,14}$');

COMMENT ON COLUMN members.current_phone_number IS 'E.164 정규형(+821012345678). 애플리케이션에서는 PhoneNumbers 유틸이 유일한 정규화 창구.';
COMMENT ON COLUMN phone_otp_attempts.phone_number IS 'E.164 정규형(+821012345678).';
COMMENT ON COLUMN phone_verifications.phone_number IS 'E.164 정규형(+821012345678).';
