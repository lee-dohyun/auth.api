package com.dh.auth.security;

import java.time.Instant;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

@Component
public class JwtProvider {

    private final JwtKeyProvider keyProvider;
    private final long accessTokenExpirationMinutes;

    public JwtProvider(JwtKeyProvider keyProvider,
                        @Value("${jwt.access-token-expiration-minutes:30}") long accessTokenExpirationMinutes) {
        this.keyProvider = keyProvider;
        this.accessTokenExpirationMinutes = accessTokenExpirationMinutes;
    }

    public String issueAccessToken(String email, String role) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTokenExpirationMinutes * 60);

        return Jwts.builder()
                .setHeaderParam("kid", keyProvider.getKeyId())
                .setSubject(email)
                .claim("role", role)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .signWith(keyProvider.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationMinutes * 60;
    }
}
