package com.dh.auth.service;

import org.springframework.http.HttpStatus;

/**
 * 관리자 등급 관리 요청을 거부할 때 던진다 (gateway#80).
 *
 * <p>{@code error} 는 admin.front 가 화면 문구로 바꿔 쓰는 기계 판독용 코드이고,
 * {@code getMessage()} 는 로그·디버깅용 설명이다.
 */
public class AdminMemberGradeException extends RuntimeException {

    private final HttpStatus status;
    private final String error;

    public AdminMemberGradeException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}
