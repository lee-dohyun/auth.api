-- OTP 발송 남용 가드(auth.api#29)가 상한을 계산하기 위한 발송 원장.
--
-- 왜 새 테이블인가: phone_otp_attempts 는 번호당 한 행만 유지하고 재발송 때 그 행을 갱신한다
-- (resend()). 그래서 "지금까지 몇 통이 실제로 나갔는가"를 셀 수 있는 곳이 아예 없었다.
-- send-otp 는 gateway PUBLIC_EXACT_PATHS 에 있는 공개 엔드포인트이고, 방어는 "번호당 60초
-- 쿨다운" 하나뿐이었다 — 번호만 바꾸면 발송량에 사실상 상한이 없었다는 뜻이다.
--
-- 이 원장은 개인정보 최소 수집 원칙에 따라 상한 계산에 필요한 것만 담는다(번호/용도/시각).
-- 보관 기간(sms.guard.retention)이 지난 행은 SmsSendGuard 가 발송 때마다 정리한다.
CREATE TABLE sms_send_logs (
    id           BIGSERIAL   PRIMARY KEY,
    phone_number VARCHAR(20) NOT NULL,
    purpose      VARCHAR(30) NOT NULL,
    sent_at      TIMESTAMP   NOT NULL
);

-- 번호당 상한 조회용.
CREATE INDEX idx_sms_send_logs_phone_sent_at ON sms_send_logs (phone_number, sent_at);
-- 전역(버스트/일일) 상한 조회 + 보관 기간 정리용.
CREATE INDEX idx_sms_send_logs_sent_at ON sms_send_logs (sent_at);
