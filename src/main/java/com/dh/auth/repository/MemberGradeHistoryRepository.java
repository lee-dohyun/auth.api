package com.dh.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.auth.entity.MemberGradeHistory;

public interface MemberGradeHistoryRepository extends JpaRepository<MemberGradeHistory, Long> {
}
