package com.dh.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class AuthDtos {

    // 메시지는 messages*.properties에서 요청 로케일(ko/en/zh/ja)에 맞춰 해석된다 — LocaleConfig 참고.

    public record SignupRequest(
            @NotBlank(message = "{validation.email.required}")
            @Email(message = "{validation.email.invalid}")
            String email,
            @NotBlank(message = "{validation.password.required}") String password,
            @NotBlank(message = "{validation.name.required}") String name,
            @NotBlank(message = "{validation.phone.required}")
            @Pattern(regexp = "^01[0-9]-?\\d{3,4}-?\\d{4}$", message = "{validation.phone.invalid}")
            String phoneNumber,
            /** 마케팅 정보 수신 동의(선택). 누락되면 동의하지 않은 것으로 본다. */
            Boolean marketingOptIn) {
    }

    public record SendPhoneOtpRequest(
            @NotBlank(message = "{validation.phone.required}")
            @Pattern(regexp = "^01[0-9]-?\\d{3,4}-?\\d{4}$", message = "{validation.phone.invalid}")
            String phoneNumber) {
    }

    public record VerifyPhoneOtpRequest(
            @NotBlank(message = "{validation.phone.required}")
            @Pattern(regexp = "^01[0-9]-?\\d{3,4}-?\\d{4}$", message = "{validation.phone.invalid}")
            String phoneNumber,
            @NotBlank(message = "{validation.otp.required}") String code) {
    }

    public record LoginRequest(String email, String password, Boolean rememberMe) {
    }

    public record MeResponse(String email, String name) {
    }

    public record FindIdRequest(
            @NotBlank(message = "{validation.name.required}") String name,
            @NotBlank(message = "{validation.joinDate.required}") String joinDate) {
    }

    public record FindIdResponse(String maskedEmail) {
    }

    public record ForgotPasswordRequest(
            @NotBlank @Email String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Email String email,
            @NotBlank String token,
            @NotBlank(message = "{validation.password.required}") String newPassword) {
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
