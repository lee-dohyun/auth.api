package com.dh.auth.security;

import java.util.Map;

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

    public record TokenResponse(String accessToken, long expiresInSeconds) {
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

    /** 이메일/비밀번호를 Keycloak Direct Access Grant(Resource Owner Password)로 검증하고 토큰을 받는다. */
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

    /** Keycloak Admin API로 사용자를 생성한다. 이메일 중복이면 true를 반환(호출부에서 409 처리). */
    public boolean createUser(String email, String name, String password) {
        String token = serviceAccountToken();

        Map<String, Object> user = Map.of(
                "username", email,
                "email", email,
                "firstName", name == null || name.isBlank() ? email : name,
                "enabled", true,
                "emailVerified", true,
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
}
