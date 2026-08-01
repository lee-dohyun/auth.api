package com.dh.auth.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * auth.api는 더 이상 자체적으로 사용자를 저장하거나 JWT를 서명하지 않는다.
 * 로그인/회원가입 요청을 Keycloak(customer realm)으로 그대로 위임하고,
 * Keycloak이 발급한 토큰을 쿠키로 내려주는 얇은 어댑터 역할만 한다.
 */
@Component
public class KeycloakClient {

    private static final String ATTR_TOKEN = "emailVerificationToken";
    private static final String ATTR_EXPIRES_AT = "emailVerificationExpiresAt";
    private static final long VERIFICATION_TOKEN_TTL_MILLIS = 24L * 60 * 60 * 1000; // 24시간

    public record TokenResponse(String accessToken, long expiresInSeconds) {
    }

    /** 로그인 시도 결과 — 비밀번호는 맞았지만 이메일 미인증이면 emailVerified=false로 구분한다. */
    public record LoginResult(boolean emailVerified, TokenResponse token) {
    }

    private final RestClient restClient;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    public KeycloakClient(
            @Value("${keycloak.url}") String keycloakUrl,
            @Value("${keycloak.realm}") String realm,
            @Value("${keycloak.client-id}") String clientId,
            @Value("${keycloak.client-secret}") String clientSecret) {
        this.restClient = RestClient.create(keycloakUrl);
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * 이메일/비밀번호를 Keycloak Direct Access Grant(Resource Owner Password)로 검증하고 토큰을 받는다.
     * emailVerified=false인 계정도 비밀번호만 맞으면 토큰 자체는 정상 발급됨(Keycloak은 ROPC에서
     * required action을 강제하지 않음) — 실제 로그인 허용 여부는 이 토큰과 별개로
     * Admin API로 emailVerified를 조회해서 컨트롤러가 판단한다.
     */
    @SuppressWarnings("unchecked")
    public TokenResponse passwordGrant(String email, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("username", email);
        form.add("password", password);
        form.add("scope", "openid profile email");

        Map<String, Object> body = restClient.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .body(form)
                .retrieve()
                .body(Map.class);

        return new TokenResponse(
                (String) body.get("access_token"),
                ((Number) body.get("expires_in")).longValue());
    }

    /** auth-api-backend 서비스 계정(Client Credentials)으로 Admin API 호출용 토큰을 받는다. */
    @SuppressWarnings("unchecked")
    private String serviceAccountToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        Map<String, Object> body = restClient.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .body(form)
                .retrieve()
                .body(Map.class);
        return (String) body.get("access_token");
    }

    /**
     * Keycloak Admin API로 사용자를 생성한다. 이메일 중복이면 true를 반환(호출부에서 409 처리).
     * emailVerified는 false로 시작 — 인증 토큰 발급/메일 발송은 호출부가 이어서
     * issueVerificationToken()을 호출해서 처리한다(재발송과 로직을 공유하기 위해 분리).
     */
    public boolean createUser(String email, String name, String password) {
        String token = serviceAccountToken();

        Map<String, Object> user = Map.of(
                "username", email,
                "email", email,
                "firstName", name == null || name.isBlank() ? email : name,
                "enabled", true,
                "emailVerified", false,
                "credentials", java.util.List.of(Map.of(
                        "type", "password",
                        "value", password,
                        "temporary", false)));

        try {
            restClient.post()
                    .uri("/admin/realms/{realm}/users", realm)
                    .header("Authorization", "Bearer " + token)
                    .body(user)
                    .retrieve()
                    .toBodilessEntity();
            return false;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409) {
                return true;
            }
            throw e;
        }
    }

    /** 방금 발급한(또는 재발급한) 이메일 인증 토큰. EmailVerificationService가 메일 본문에 넣는다. */
    public record VerificationToken(String token) {
    }

    /** 새 인증 토큰을 발급해서 사용자 attributes에 저장하고 반환한다 — 회원가입 직후, 또는 재발송 요청 시 사용. */
    public VerificationToken issueVerificationToken(String email) {
        String token = serviceAccountToken();
        Map<String, Object> user = findUserByEmail(email, token);
        String userId = (String) user.get("id");

        String verificationToken = UUID.randomUUID().toString();
        long expiresAt = Instant.now().toEpochMilli() + VERIFICATION_TOKEN_TTL_MILLIS;

        Map<String, Object> fields = Map.of(
                "attributes", Map.of(
                        ATTR_TOKEN, List.of(verificationToken),
                        ATTR_EXPIRES_AT, List.of(String.valueOf(expiresAt))));
        restClient.put()
                .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                .header("Authorization", "Bearer " + token)
                .body(fields)
                .retrieve()
                .toBodilessEntity();

        return new VerificationToken(verificationToken);
    }

    /**
     * 이메일 인증 토큰을 검증한다. 일치 + 만료 전이면 emailVerified=true로 전환하고 true를 반환,
     * 그 외(사용자 없음/토큰 불일치/만료)는 false를 반환한다.
     */
    @SuppressWarnings("unchecked")
    public boolean verifyEmail(String email, String submittedToken) {
        String token = serviceAccountToken();
        Map<String, Object> user;
        try {
            user = findUserByEmail(email, token);
        } catch (IllegalStateException e) {
            return false;
        }
        String userId = (String) user.get("id");

        Map<String, Object> attributes = (Map<String, Object>) user.get("attributes");
        String storedToken = firstAttr(attributes, ATTR_TOKEN);
        String expiresAtRaw = firstAttr(attributes, ATTR_EXPIRES_AT);

        if (storedToken == null || !storedToken.equals(submittedToken)) {
            return false;
        }
        if (expiresAtRaw == null || Long.parseLong(expiresAtRaw) < Instant.now().toEpochMilli()) {
            return false;
        }

        Map<String, Object> fields = Map.of(
                "emailVerified", true,
                "attributes", Map.of());
        restClient.put()
                .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                .header("Authorization", "Bearer " + token)
                .body(fields)
                .retrieve()
                .toBodilessEntity();
        return true;
    }

    /** 이메일로 사용자를 조회해서 emailVerified 여부만 반환한다 (로그인 시 auth.api가 검사). */
    public boolean isEmailVerified(String email) {
        String token = serviceAccountToken();
        Map<String, Object> user = findUserByEmail(email, token);
        Object verified = user.get("emailVerified");
        return Boolean.TRUE.equals(verified);
    }

    @SuppressWarnings("unchecked")
    private String firstAttr(Map<String, Object> attributes, String key) {
        if (attributes == null) {
            return null;
        }
        Object value = attributes.get(key);
        if (value instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return null;
    }

    /** 이메일/이름/비밀번호 중 값이 있는 항목만 Keycloak Admin API로 갱신한다. */
    public com.dh.auth.dto.AuthDtos.MeResponse updateUser(
            String currentEmail, String newEmail, String newName, String newPassword) {
        String token = serviceAccountToken();
        Map<String, Object> user = findUserByEmail(currentEmail, token);
        String userId = (String) user.get("id");

        Map<String, Object> fields = new java.util.HashMap<>();
        if (newEmail != null && !newEmail.isBlank()) {
            fields.put("email", newEmail);
            fields.put("username", newEmail);
        }
        if (newName != null && !newName.isBlank()) {
            fields.put("firstName", newName);
        }
        if (!fields.isEmpty()) {
            restClient.put()
                    .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                    .header("Authorization", "Bearer " + token)
                    .body(fields)
                    .retrieve()
                    .toBodilessEntity();
        }

        if (newPassword != null && !newPassword.isBlank()) {
            Map<String, Object> credential = Map.of(
                    "type", "password",
                    "value", newPassword,
                    "temporary", false);
            restClient.put()
                    .uri("/admin/realms/{realm}/users/{id}/reset-password", realm, userId)
                    .header("Authorization", "Bearer " + token)
                    .body(credential)
                    .retrieve()
                    .toBodilessEntity();
        }

        String finalEmail = (newEmail != null && !newEmail.isBlank()) ? newEmail : currentEmail;
        String finalName = (newName != null && !newName.isBlank()) ? newName : (String) user.get("firstName");
        return new com.dh.auth.dto.AuthDtos.MeResponse(finalEmail, finalName);
    }

    /** Keycloak Admin API로 사용자를 완전히 삭제한다. */
    public void deleteUser(String email) {
        String token = serviceAccountToken();
        Map<String, Object> user = findUserByEmail(email, token);
        String userId = (String) user.get("id");

        restClient.delete()
                .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findUserByEmail(String email, String token) {
        java.util.List<Map<String, Object>> users = restClient.get()
                .uri("/admin/realms/{realm}/users?email={email}&exact=true", realm, email)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(java.util.List.class);
        if (users == null || users.isEmpty()) {
            throw new IllegalStateException("사용자를 찾을 수 없음: " + email);
        }
        return users.get(0);
    }
}
