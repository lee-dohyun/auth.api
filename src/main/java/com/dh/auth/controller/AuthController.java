package com.dh.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;

import com.dh.auth.dto.AuthDtos.LoginRequest;
import com.dh.auth.dto.AuthDtos.MeResponse;
import com.dh.auth.dto.AuthDtos.SignupRequest;
import com.dh.auth.security.KeycloakClient;

@RestController
public class AuthController {

    private static final String ACCESS_TOKEN_COOKIE = "ACCESS_TOKEN";
    private static final String COOKIE_DOMAIN = ".leedohyun.com";

    private final KeycloakClient keycloakClient;

    public AuthController(KeycloakClient keycloakClient) {
        this.keycloakClient = keycloakClient;
    }

    @PostMapping("/api/auth/signup")
    public ResponseEntity<Void> signup(@RequestBody SignupRequest request) {
        boolean alreadyExists = keycloakClient.createUser(request.email(), request.name(), request.password());
        if (alreadyExists) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<Void> login(@RequestBody LoginRequest request) {
        KeycloakClient.TokenResponse token;
        try {
            token = keycloakClient.passwordGrant(request.email(), request.password());
        } catch (HttpClientErrorException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
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
        ResponseCookie cookie = ResponseCookie.from(ACCESS_TOKEN_COOKIE, "")
                .domain(COOKIE_DOMAIN)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        return ResponseEntity.ok()
                .header("Set-Cookie", cookie.toString())
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
        return ResponseEntity.ok(new MeResponse(email, name));
    }
}
