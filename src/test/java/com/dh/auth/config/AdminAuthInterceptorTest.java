package com.dh.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 관리 API 인가 검사 (auth.api#40).
 *
 * <p>화면(admin.front 미들웨어)에서 버튼을 숨기는 것은 방어선이 아니다. 토큰만 유효하면 통과시키는
 * 구현은 다른 역할의 staff 계정이 API 를 직접 호출해 우회할 수 있다(product.api#25 의 실제 사례).
 */
class AdminAuthInterceptorTest {

    private final AdminJwtVerifier verifier = mock(AdminJwtVerifier.class);
    private final AdminAuthInterceptor interceptor = new AdminAuthInterceptor(verifier);

    @Test
    @DisplayName("MEMBER_MANAGER 역할이면 회원 API를 통과한다")
    void member_manager는_통과한다() throws Exception {
        givenToken(new AdminPrincipal("admin@posselect.com", Set.of("MEMBER_MANAGER")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request("DELETE", "/api/admin/members/abc"), response, null);

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("SYSTEM_ADMIN 역할이면 회원 API를 통과한다")
    void system_admin도_통과한다() throws Exception {
        givenToken(new AdminPrincipal("root@posselect.com", Set.of("SYSTEM_ADMIN")));

        boolean allowed = interceptor.preHandle(
                request("GET", "/api/admin/members"), new MockHttpServletResponse(), null);

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("다른 관리 역할(ORDER_MANAGER)만 가진 계정은 403으로 거부된다")
    void 역할이_다르면_거부된다() throws Exception {
        givenToken(new AdminPrincipal("order@posselect.com", Set.of("ORDER_MANAGER")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request("DELETE", "/api/admin/members/abc"), response, null);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("토큰이 없으면 403으로 거부된다")
    void 토큰이_없으면_거부된다() throws Exception {
        when(verifier.verify(null)).thenReturn(null);

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/admin/members"), response, null);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("GET(목록 조회)도 인가를 요구한다 — 회원 목록 자체가 개인정보다")
    void 조회에도_인가가_필요하다() throws Exception {
        givenToken(new AdminPrincipal("order@posselect.com", Set.of("ORDER_MANAGER")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request("GET", "/api/admin/members"), response, null);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("PATH_ROLES에 등록되지 않은 관리 경로는 통과가 아니라 거부된다 (설정 누락 fail-closed)")
    void 미등록_경로는_거부된다() throws Exception {
        givenToken(new AdminPrincipal("root@posselect.com", Set.of("SYSTEM_ADMIN")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request("GET", "/api/admin/unknown"), response, null);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    private void givenToken(AdminPrincipal principal) {
        when(verifier.verify(anyString())).thenReturn(principal);
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "Bearer dummy-token");
        return request;
    }
}
