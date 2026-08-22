package com.dh.auth.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 실제로 나간(또는 나갈) SMS 1건 = 1행. 발송 상한 계산의 유일한 근거다(auth.api#29).
 *
 * <p>{@link PhoneOtpAttempt} 와 혼동하지 말 것 — 그쪽은 번호당 한 행을 재사용하는 <b>인증 세션</b>이라
 * 누적 발송량을 셀 수 없다. 이 원장은 지우지 않는 한 건별로 남는다.
 */
@Entity
@Table(name = "sms_send_logs")
public class SmsSendLog {

    /** 발송 용도. 지금은 OTP 뿐이지만 주문 알림 등이 붙어도 전역 상한은 전부 합산해서 본다. */
    public static final String PURPOSE_OTP = "OTP";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "purpose", nullable = false, length = 30)
    private String purpose;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    protected SmsSendLog() {
    }

    public SmsSendLog(String phoneNumber, String purpose, LocalDateTime sentAt) {
        this.phoneNumber = phoneNumber;
        this.purpose = purpose;
        this.sentAt = sentAt;
    }

    public Long getId() {
        return id;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getPurpose() {
        return purpose;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }
}
