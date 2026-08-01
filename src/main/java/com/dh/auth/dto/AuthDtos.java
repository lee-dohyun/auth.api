package com.dh.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class AuthDtos {

    public record SignupRequest(
            @NotBlank(message = "이메일을 입력하세요.") @Email(message = "올바른 이메일 형식이 아닙니다.") String email,
            @NotBlank(message = "비밀번호를 입력하세요.") String password,
            @NotBlank(message = "이름을 입력하세요.") String name) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record MeResponse(String email, String name) {
    }

    public record UpdateMeRequest(String email, String name, String password) {
    }

    public record VerifyEmailRequest(
            @NotBlank String email,
            @NotBlank String token) {
    }

    public record ResendVerificationRequest(
            @NotBlank @Email String email) {
    }

    public record ErrorResponse(String error) {
    }
}
