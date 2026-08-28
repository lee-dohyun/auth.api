package com.dh.auth.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Keycloak {@code staff} realm 의 JWT 를 직접 검증한다 (product.api 의 같은 이름 클래스와 동일한 방식).
 *
 * <p><b>이 서비스가 두 개의 realm 을 상대한다는 점에 주의.</b> 고객 로그인은 {@code customer} realm 을
 * 쓰고({@link com.dh.auth.security.KeycloakClient}), 관리자 화면(admin.front)이 보내는 토큰은
 * {@code staff} realm 에서 발급된다. 둘은 서명 키도 issuer 도 다르므로 검증 경로를 섞으면 안 된다.
 *
 * <p>admin.front 가 받은 토큰을 그대로 Authorization 헤더로 넘기면 여기서 재검증하므로,
 * 서비스 간에 공유·로테이션할 비밀값이 따로 필요 없다.
 */
@Component
public class AdminJwtVerifier {

    // Keycloak 은 내부 클러스터 URL 로 요청받아도 issuer 는 항상 공개 URL 로 찍는다.
    private final String expectedIssuer;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, RSAKey> keyCache = new ConcurrentHashMap<>();
    private final String jwksUri;

    public AdminJwtVerifier(
            @Value("${admin.staff-realm-url:http://keycloak-service.keycloak.svc.cluster.local/realms/staff}")
            String staffRealmUrl,
            @Value("${admin.staff-realm-issuer:https://keycloak.posselect.com/realms/staff}")
            String expectedIssuer) {
        this.jwksUri = staffRealmUrl + "/protocol/openid-connect/certs";
        this.expectedIssuer = expectedIssuer;
    }

    /** 유효하면 {@link AdminPrincipal}, 아니면 null. 역할 확인은 호출부의 몫이다. */
    public AdminPrincipal verify(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return null;
        }
        try {
            SignedJWT signedJwt = SignedJWT.parse(bearerToken);
            RSAKey rsaKey = resolveKey(signedJwt.getHeader().getKeyID());
            if (rsaKey == null || !signedJwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey()))) {
                return null;
            }
            JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
                return null;
            }
            if (!expectedIssuer.equals(claims.getIssuer())) {
                return null;
            }
            return new AdminPrincipal(claims.getStringClaim("email"), extractRealmRoles(claims));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Keycloak 은 realm 역할을 {@code realm_access.roles} 배열에 싣는다
     * (admin.front 의 {@code lib/auth.ts} 도 같은 경로를 읽는다 — 둘이 어긋나면
     * 화면과 API 의 판정이 갈린다).
     */
    private static Set<String> extractRealmRoles(JWTClaimsSet claims) {
        Object realmAccess = claims.getClaim("realm_access");
        if (!(realmAccess instanceof Map<?, ?> map)) {
            return Set.of();
        }
        if (!(map.get("roles") instanceof List<?> roles)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object role : roles) {
            if (role instanceof String name) {
                result.add(name);
            }
        }
        return Set.copyOf(result);
    }

    private RSAKey resolveKey(String kid) throws Exception {
        RSAKey cached = keyCache.get(kid);
        if (cached != null) {
            return cached;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUri))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JWKSet jwkSet = JWKSet.parse(response.body());
        RSAKey key = (RSAKey) jwkSet.getKeyByKeyId(kid);
        if (key != null) {
            keyCache.put(kid, key);
        }
        return key;
    }
}
