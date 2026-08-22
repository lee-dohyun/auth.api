package com.dh.auth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.auth.entity.MemberGrade;

public interface MemberGradeRepository extends JpaRepository<MemberGrade, Long> {

    Optional<MemberGrade> findByIsDefaultTrue();

    Optional<MemberGrade> findByCode(String code);

    /**
     * 기준 금액이 정해진 등급을 높은 순으로. 산정은 "내 금액 이하인 첫 등급"을 고르는 것이라
     * 정렬이 곧 규칙이다. {@code min_spend_amount} 가 NULL 인 등급은 산정 대상이 아니다
     * (기준이 정해지지 않은 등급을 자동으로 부여할 수는 없다).
     */
    List<MemberGrade> findByMinSpendAmountIsNotNullOrderByMinSpendAmountDesc();
}
