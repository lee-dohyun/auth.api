package com.dh.auth.controller;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;

import com.dh.auth.dto.AuthDtos.ErrorResponse;
import com.dh.auth.dto.AuthDtos.FindIdRequest;
import com.dh.auth.dto.AuthDtos.FindIdResponse;
import com.dh.auth.dto.AuthDtos.ForgotPasswordRequest;
import com.dh.auth.dto.AuthDtos.LoginRequest;
import com.dh.auth.dto.AuthDtos.MeResponse;
import com.dh.auth.dto.AuthDtos.ResendVerificationRequest;
import com.dh.auth.dto.AuthDtos.ResetPasswordRequest;
import com.dh.auth.dto.AuthDtos.SignupRequest;
import com.dh.auth.dto.AuthDtos.UpdateMeRequest;
import com.dh.auth.dto.AuthDtos.VerifyEmailRequest;
import com.dh.auth.security.KeycloakClient;
import com.dh.auth.service.EmailVerificationService;
import com.dh.auth.service.MemberService;
import com.dh.auth.service.PhoneVerificationService;

import jakarta.validation.Valid;

@Validated
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String ACCESS_TOKEN_COOKIE = "ACCESS_TOKEN";
    private static final String REFRESH_TOKEN_COOKIE = "REFRESH_TOKEN";
    private static final String REFRESH_TOKEN_PATH = "/api/auth";

    private final String cookieDomain;
    private final KeycloakClient keycloakClient;
    private final EmailVerificationService emailVerificationService;
    private final PhoneVerificationService phoneVerificationService;
    private final MemberService memberService;

    public AuthController(
            KeycloakClient keycloakClient,
            EmailVerificationService emailVerificationService,
            PhoneVerificationService phoneVerificationService,
            MemberService memberService,
            @Value("${app.cookie-domain}") String cookieDomain) {
        this.keycloakClient = keycloakClient;
        this.emailVerificationService = emailVerificationService;
        this.phoneVerificationService = phoneVerificationService;
        this.memberService = memberService;
        this.cookieDomain = cookieDomain;
    }

    @PostMapping("/api/auth/signup")
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest request) {
        if (!phoneVerificationService.isRecentlyVerified(request.phoneNumber())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("PHONE_NOT_VERIFIED"));
        }

        boolean alreadyExists = keycloakClient.createUser(request.email(), request.name(), request.password());
        if (alreadyExists) {
            KeycloakClient.UserInfo existingUser = keycloakClient.findUser(request.email());
            if (!memberService.existsByKeycloakUserId(existingUser.id())) {
                log.warn("로컬 DB에 연동되지 않은 Keycloak 좀비 유저를 삭제하고 재가입을 시도합니다. email={}", request.email());
                keycloakClient.deleteUser(request.email());
                keycloakClient.createUser(request.email(), request.name(), request.password());
            } else {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        }

        KeycloakClient.UserInfo createdUser = keycloakClient.findUser(request.email());
        try {
            memberService.createMemberForSignup(
                    createdUser.id(), request.phoneNumber(), Boolean.TRUE.equals(request.marketingOptIn()));

            KeycloakClient.VerificationToken verification = keycloakClient.issueVerificationToken(request.email());
            emailVerificationService.sendVerificationEmail(request.email(), request.name(), verification.token());
        } catch (Exception e) {
            log.error("로컬 도메인 가입 처리 중 오류 발생. Keycloak 유저를 롤백(삭제)합니다. email={}", request.email(), e);
            keycloakClient.deleteUser(request.email());
            throw e;
        }

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

    @GetMapping("/api/auth/callback")
    public ResponseEntity<?> callback(
            @org.springframework.web.bind.annotation.RequestParam("code") String code,
            @org.springframework.web.bind.annotation.RequestParam("state") String state,
            @CookieValue(value = "oauth_state", required = false) String stateCookie,
            jakarta.servlet.http.HttpServletRequest request) {

        if (stateCookie == null || !stateCookie.equals(state)) {
            log.warn("소셜 로그인 콜백 state 불일치 — CSRF 의심 또는 만료된 요청");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        String scheme = request.getHeader("X-Forwarded-Proto") != null ? request.getHeader("X-Forwarded-Proto") : request.getScheme();
        String host = request.getHeader("X-Forwarded-Host") != null ? request.getHeader("X-Forwarded-Host") : request.getServerName();
        String port = request.getHeader("X-Forwarded-Port");
        if (port != null && !port.equals("80") && !port.equals("443")) {
            host = host + ":" + port;
        } else if (port == null && request.getServerPort() != 80 && request.getServerPort() != 443) {
            host = host + ":" + request.getServerPort();
        }

        String redirectUri = scheme + "://" + host + "/api/auth/callback";

        KeycloakClient.TokenResponse token;
        try {
            token = keycloakClient.authorizationCodeGrant(code, redirectUri);
        } catch (HttpClientErrorException e) {
            log.error("Failed to get token from Keycloak using code. Response: {}", e.getResponseBodyAsString(), e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 액세스 토큰으로 사용자 정보 조회 후 로컬 Member 엔티티가 없으면 생성 (최초 로그인)
        try {
            KeycloakClient.UserInfo user = keycloakClient.userInfo(token.accessToken());
            if (!memberService.existsByKeycloakUserId(user.id())) {
                memberService.createMemberForSocialLogin(user.id());
                log.info("소셜 로그인 신규 가입 - 고객번호={}, 이메일={}", user.id(), user.email());
            }
            log.info("소셜 로그인 성공 - 고객번호={}, 이메일={}, 고객명={}", user.id(), user.email(), user.name());
        } catch (Exception e) {
            log.error("소셜 로그인 사용자 동기화 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        ResponseCookie accessCookie = ResponseCookie.from(ACCESS_TOKEN_COOKIE, token.accessToken())
                .domain(cookieDomain)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(token.expiresInSeconds())
                .sameSite("Lax")
                .build();

        ResponseCookie refreshCookie = refreshCookie(token.refreshToken(), token.refreshExpiresInSeconds());
        ResponseCookie clearedState = ResponseCookie.from("oauth_state", "")
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .secure(true)
                .build();

        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", "/mypage")
                .header("Set-Cookie", accessCookie.toString())
                .header("Set-Cookie", refreshCookie.toString())
                .header("Set-Cookie", clearedState.toString())
                .build();
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        KeycloakClient.TokenResponse token;
        try {
            token = keycloakClient.passwordGrant(request.email(), request.password());
        } catch (HttpClientErrorException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        KeycloakClient.UserInfo user = keycloakClient.findUser(request.email());
        if (!user.emailVerified()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("EMAIL_NOT_VERIFIED"));
        }

        log.info("로그인 성공 - 고객번호={}, 이메일={}, 고객명={}", user.id(), user.email(), user.name());

        ResponseCookie accessCookie = ResponseCookie.from(ACCESS_TOKEN_COOKIE, token.accessToken())
                .domain(cookieDomain)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(token.expiresInSeconds())
                .sameSite("Lax")
                .build();

        ResponseEntity.BodyBuilder response = ResponseEntity.ok().header("Set-Cookie", accessCookie.toString());
        if (Boolean.TRUE.equals(request.rememberMe())) {
            response.header("Set-Cookie", refreshCookie(token.refreshToken(), token.refreshExpiresInSeconds()).toString());
        }
        return response.build();
    }

    /**
     * "로그인 상태 유지"로 로그인한 경우 프론트가 액세스 토큰 만료 전에 주기 호출하는 엔드포인트.
     * REFRESH_TOKEN 쿠키가 없거나 만료/폐기된 경우 401을 반환 — 이 경우 프론트는 재발급을
     * 포기하고 다음 보호된 페이지 접근 시 자연스럽게 재로그인으로 유도된다.
     */
    @PostMapping("/api/auth/refresh")
    public ResponseEntity<Void> refresh(
            @CookieValue(value = REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        KeycloakClient.TokenResponse token;
        try {
            token = keycloakClient.refreshToken(refreshToken);
        } catch (HttpClientErrorException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("Set-Cookie", clearedCookie(ACCESS_TOKEN_COOKIE, "/").toString())
                    .header("Set-Cookie", clearedCookie(REFRESH_TOKEN_COOKIE, REFRESH_TOKEN_PATH).toString())
                    .build();
        }

        ResponseCookie accessCookie = ResponseCookie.from(ACCESS_TOKEN_COOKIE, token.accessToken())
                .domain(cookieDomain)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(token.expiresInSeconds())
                .sameSite("Lax")
                .build();

        return ResponseEntity.ok()
                .header("Set-Cookie", accessCookie.toString())
                .header("Set-Cookie", refreshCookie(token.refreshToken(), token.refreshExpiresInSeconds()).toString())
                .build();
    }

    /**
     * 이름 + 가입일로 이메일을 찾는다. 정확히 하나만 일치하면 마스킹된 이메일을 200으로,
     * 일치하는 계정이 없거나 특정할 수 없으면(동명이인 등) 404를 반환한다.
     */
    @PostMapping("/api/auth/find-id")
    public ResponseEntity<FindIdResponse> findId(@Valid @RequestBody FindIdRequest request) {
        return keycloakClient.findEmailByNameAndJoinDate(request.name(), request.joinDate())
                .map(email -> ResponseEntity.ok(new FindIdResponse(maskEmail(email))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * 비밀번호 재설정 메일 발송. 존재하지 않는 이메일이어도 200을 반환한다(resend-verification과
     * 동일하게 이메일 존재 여부를 노출하지 않기 위함).
     */
    @PostMapping("/api/auth/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        try {
            KeycloakClient.UserInfo user = keycloakClient.findUser(request.email());
            KeycloakClient.VerificationToken reset = keycloakClient.issuePasswordResetToken(request.email());
            emailVerificationService.sendPasswordResetEmail(request.email(), user.name(), reset.token());
        } catch (RuntimeException e) {
            // 사용자를 못 찾는 경우 등 — 존재 여부를 노출하지 않기 위해 조용히 무시
        }
        return ResponseEntity.ok().build();
    }

    /** 비밀번호 재설정 메일의 링크가 호출하는 엔드포인트. 성공 시 200, 토큰이 없거나 만료/불일치면 400. */
    @PostMapping("/api/auth/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        boolean reset = keycloakClient.resetPassword(request.email(), request.token(), request.newPassword());
        if (!reset) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.ok()
                .header("Set-Cookie", clearedCookie(ACCESS_TOKEN_COOKIE, "/").toString())
                .header("Set-Cookie", clearedCookie(REFRESH_TOKEN_COOKIE, REFRESH_TOKEN_PATH).toString())
                .build();
    }

    @DeleteMapping("/api/auth/me")
    public ResponseEntity<Void> deleteMe(
            @RequestHeader(value = "X-User-Email", required = false) String email) {
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            KeycloakClient.UserInfo user = keycloakClient.findUser(email);
            memberService.withdrawMember(user.id());
        } catch (Exception e) {
            log.warn("로컬 회원 탈퇴 상태 업데이트 중 오류 발생: email={}", email, e);
        }

        try {
            keycloakClient.deleteUser(email);
        } catch (Exception e) {
            log.warn("Keycloak 사용자 삭제 중 오류 발생: email={}", email, e);
        }

        return ResponseEntity.ok()
                .header("Set-Cookie", clearedCookie(ACCESS_TOKEN_COOKIE, "/").toString())
                .header("Set-Cookie", clearedCookie(REFRESH_TOKEN_COOKIE, REFRESH_TOKEN_PATH).toString())
                .build();
    }

    private ResponseCookie clearedCookie(String name, String path) {
        return ResponseCookie.from(name, "")
                .domain(cookieDomain)
                .httpOnly(true)
                .secure(true)
                .path(path)
                .maxAge(0)
                .sameSite("Lax")
                .build();
    }

    private ResponseCookie refreshCookie(String refreshToken, long expiresInSeconds) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, refreshToken)
                .domain(cookieDomain)
                .httpOnly(true)
                .secure(true)
                .path(REFRESH_TOKEN_PATH)
                .maxAge(expiresInSeconds)
                .sameSite("Lax")
                .build();
    }

    /** user@example.com -> u***@example.com */
    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return email;
        }
        return email.charAt(0) + "***" + email.substring(at);
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
