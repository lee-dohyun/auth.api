package com.dh.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.auth.entity.MemberGrade;

public interface MemberGradeRepository extends JpaRepository<MemberGrade, Long> {

    Optional<MemberGrade> findByIsDefaultTrue();

    Optional<MemberGrade> findByCode(String code);
}
