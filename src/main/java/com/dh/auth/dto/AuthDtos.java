package com.dh.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class AuthDtos {

    public record SignupRequest(
            @NotBlank(message = "이메일을 입력하세요.") @Email(message = "올바른 이메일 형식이 아닙니다.") String email,
            @NotBlank(message = "비밀번호를 입력하세요.") String password,
            @NotBlank(message = "이름을 입력하세요.") String name,
            @NotBlank(message = "휴대폰 번호를 입력하세요.")
            @Pattern(regexp = "^01[0-9]-?\\d{3,4}-?\\d{4}$", message = "올바른 휴대폰 번호 형식이 아닙니다.")
            String phoneNumber,
            /** 마케팅 정보 수신 동의(선택). 누락되면 동의하지 않은 것으로 본다. */
            Boolean marketingOptIn) {
    }

    public record SendPhoneOtpRequest(
            @NotBlank(message = "휴대폰 번호를 입력하세요.")
            @Pattern(regexp = "^01[0-9]-?\\d{3,4}-?\\d{4}$", message = "올바른 휴대폰 번호 형식이 아닙니다.")
            String phoneNumber) {
    }

    public record VerifyPhoneOtpRequest(
            @NotBlank(message = "휴대폰 번호를 입력하세요.")
            @Pattern(regexp = "^01[0-9]-?\\d{3,4}-?\\d{4}$", message = "올바른 휴대폰 번호 형식이 아닙니다.")
            String phoneNumber,
            @NotBlank(message = "인증번호를 입력하세요.") String code) {
    }

    public record LoginRequest(String email, String password, Boolean rememberMe) {
    }

    public record MeResponse(String email, String name) {
    }

    public record FindIdRequest(
            @NotBlank(message = "이름을 입력하세요.") String name,
            @NotBlank(message = "가입일을 입력하세요.") String joinDate) {
    }

    public record FindIdResponse(String maskedEmail) {
    }

    public record ForgotPasswordRequest(
            @NotBlank @Email String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Email String email,
            @NotBlank String token,
            @NotBlank(message = "비밀번호를 입력하세요.") String newPassword) {
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
