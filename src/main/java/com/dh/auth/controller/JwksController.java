package com.dh.auth.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.auth.security.JwtKeyProvider;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * gateway가 서명 검증에 쓸 공개키를 JWKS 표준 포맷으로 노출한다.
 */
@RestController
public class JwksController {

    private final JwtKeyProvider keyProvider;

    public JwksController(JwtKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        RSAKey rsaKey = new RSAKey.Builder(keyProvider.getPublicKey())
                .keyID(keyProvider.getKeyId())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .build();

        return new com.nimbusds.jose.jwk.JWKSet(rsaKey).toJSONObject();
    }
}
