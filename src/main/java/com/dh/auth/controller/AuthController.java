package com.dh.auth.controller;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;

import com.dh.auth.dto.AuthDtos.ErrorResponse;
import com.dh.auth.dto.AuthDtos.LoginRequest;
import com.dh.auth.dto.AuthDtos.MeResponse;
import com.dh.auth.dto.AuthDtos.ResendVerificationRequest;
import com.dh.auth.dto.AuthDtos.SignupRequest;
import com.dh.auth.dto.AuthDtos.UpdateMeRequest;
import com.dh.auth.dto.AuthDtos.VerifyEmailRequest;
import com.dh.auth.security.KeycloakClient;
import com.dh.auth.service.EmailVerificationService;

import jakarta.validation.Valid;

@Validated
@RestController
public class AuthController {

    private static final String ACCESS_TOKEN_COOKIE = "ACCESS_TOKEN";
    private static final String COOKIE_DOMAIN = ".leedohyun.com";

    private final KeycloakClient keycloakClient;
    private final EmailVerificationService emailVerificationService;

    public AuthController(KeycloakClient keycloakClient, EmailVerificationService emailVerificationService) {
        this.keycloakClient = keycloakClient;
        this.emailVerificationService = emailVerificationService;
    }

    @PostMapping("/api/auth/signup")
    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest request) {
        boolean alreadyExists = keycloakClient.createUser(request.email(), request.name(), request.password());
        if (alreadyExists) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        KeycloakClient.VerificationToken verification = keycloakClient.issueVerificationToken(request.email());
        emailVerificationService.sendVerificationEmail(request.email(), request.name(), verification.token());

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** 인증 메일의 링크가 호출하는 엔드포인트. 성공 시 200, 토큰이 없거나 만료/불일치면 400. */
    @PostMapping("/api/auth/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        boolean verified = keycloakClient.verifyEmail(request.email(), request.token());
        if (!verified) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return ResponseEntity.ok().build();
    }

    /**
     * 인증 메일 재발송. 존재하지 않는 이메일이어도 200을 반환한다(이메일 존재 여부를 응답으로
     * 유추할 수 없게 하기 위함 — 이미 가입된 이메일이라는 정보만 노출되는 signup의 409와는
     * 성격이 다른 엔드포인트라 여기서는 굳이 구분해서 알려주지 않는다).
     */
    @PostMapping("/api/auth/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        try {
            KeycloakClient.VerificationToken verification = keycloakClient.issueVerificationToken(request.email());
            emailVerificationService.sendVerificationEmail(request.email(), null, verification.token());
        } catch (RuntimeException e) {
            // 사용자를 못 찾는 경우 등 — 존재 여부를 노출하지 않기 위해 조용히 무시
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        KeycloakClient.TokenResponse token;
        try {
            token = keycloakClient.passwordGrant(request.email(), request.password());
        } catch (HttpClientErrorException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (!keycloakClient.isEmailVerified(request.email())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("EMAIL_NOT_VERIFIED"));
        }

        ResponseCookie cookie = ResponseCookie.from(ACCESS_TOKEN_COOKIE, token.accessToken())
                .domain(COOKIE_DOMAIN)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(token.expiresInSeconds())
                .sameSite("Lax")
                .build();

        return ResponseEntity.ok()
                .header("Set-Cookie", cookie.toString())
                .build();
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.ok()
                .header("Set-Cookie", clearedCookie().toString())
                .build();
    }

    @DeleteMapping("/api/auth/me")
    public ResponseEntity<Void> deleteMe(
            @RequestHeader(value = "X-User-Email", required = false) String email) {
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        keycloakClient.deleteUser(email);
        return ResponseEntity.ok()
                .header("Set-Cookie", clearedCookie().toString())
                .build();
    }

    private ResponseCookie clearedCookie() {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE, "")
                .domain(COOKIE_DOMAIN)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
    }

    /**
     * gateway가 Keycloak 토큰을 검증한 뒤 넣어주는 헤더를 그대로 신뢰해서 사용자 정보를 돌려준다.
     * (gateway를 거치지 않고 auth-api에 직접 호출하는 경우 헤더가 없으므로 401)
     */
    @GetMapping("/api/auth/me")
    public ResponseEntity<MeResponse> me(
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @RequestHeader(value = "X-User-Name", required = false) String name) {
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String decodedName = name == null ? null : URLDecoder.decode(name, StandardCharsets.UTF_8);
        return ResponseEntity.ok(new MeResponse(email, decodedName));
    }

    @PutMapping("/api/auth/me")
    public ResponseEntity<MeResponse> updateMe(
            @RequestHeader(value = "X-User-Email", required = false) String currentEmail,
            @RequestBody UpdateMeRequest request) {
        if (currentEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        MeResponse updated = keycloakClient.updateUser(
                currentEmail, request.email(), request.name(), request.password());
        return ResponseEntity.ok(updated);
    }
}
