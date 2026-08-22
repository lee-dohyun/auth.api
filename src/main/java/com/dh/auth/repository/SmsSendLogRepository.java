package com.dh.auth.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.auth.entity.SmsSendLog;

public interface SmsSendLogRepository extends JpaRepository<SmsSendLog, Long> {

    /** 번호당 상한 — 이 번호로 {@code after} 이후 몇 통이 나갔는가. */
    long countByPhoneNumberAndSentAtAfter(String phoneNumber, LocalDateTime after);

    /** 전역 상한 — 용도를 가리지 않고 {@code after} 이후 몇 통이 나갔는가. */
    long countBySentAtAfter(LocalDateTime after);

    /** 보관 기간이 지난 원장 정리. 상한 계산에 쓰지 않는 데이터를 남겨 둘 이유가 없다. */
    void deleteBySentAtBefore(LocalDateTime before);
}
