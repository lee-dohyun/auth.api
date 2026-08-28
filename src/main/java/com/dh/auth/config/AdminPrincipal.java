package com.dh.auth.config;

import java.util.Set;

/**
 * Keycloak {@code staff} realm 토큰에서 뽑아낸 관리자 신원.
 *
 * <p>이 객체가 존재한다는 것은 "staff realm 의 유효한 토큰"이라는 뜻일 뿐,
 * 무엇을 해도 된다는 뜻이 아니다 — 역할 검사는 {@link #hasAnyRole(String...)} 로 따로 한다.
 */
public record AdminPrincipal(String email, Set<String> roles) {

    public boolean hasAnyRole(String... candidates) {
        for (String candidate : candidates) {
            if (roles.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
