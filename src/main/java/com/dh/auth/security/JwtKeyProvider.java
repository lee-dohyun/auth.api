package com.dh.auth.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * POC 규모라 서버 인스턴스 하나가 기동 시 RSA 키쌍을 한 번 생성해서 계속 쓴다.
 * 재시작하면 kid가 바뀌어 이전에 발급된 토큰은 자동 무효화된다.
 */
@Component
public class JwtKeyProvider {

    private final KeyPair keyPair;
    private final String keyId = UUID.randomUUID().toString();

    public JwtKeyProvider() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keyPair = generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA 키쌍 생성 실패", e);
        }
    }

    public RSAPrivateKey getPrivateKey() {
        return (RSAPrivateKey) keyPair.getPrivate();
    }

    public RSAPublicKey getPublicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    public String getKeyId() {
        return keyId;
    }
}
