package com.dh.auth.config;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code /api/admin/**} 은 admin.front(Keycloak staff realm 로그인)만 호출할 수 있어야 하므로
 * admin.front 가 전달하는 Authorization Bearer 토큰을 여기서 다시 검증한다.
 *
 * <p>admin.front 의 미들웨어는 admin.posselect.com 을 거칠 때만 도는 방어선이다. 백엔드가 역할을
 * 보지 않으면 다른 역할의 staff 계정이 API 를 직접 호출해 그 제한을 우회할 수 있다(product.api#25).
 *
 * <p><b>product.api 의 같은 이름 클래스와 다른 점</b>: 저쪽은 GET 을 공개로 두지만
 * 여기서는 <b>GET 도 막는다.</b> 회원 목록은 그 자체가 개인정보라 조회에도 인가가 필요하다.
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AdminAuthInterceptor.class);

    /** 인증된 {@link AdminPrincipal} 을 담아두는 요청 속성 키 — 컨트롤러가 "누가 했는가"를 로그에 남길 때 쓴다. */
    public static final String PRINCIPAL_ATTRIBUTE = "adminPrincipal";

    /**
     * 경로 접두사 -> 필요 역할(하나라도 있으면 통과). admin.front {@code lib/menu.ts} 의
     * apiPrefixes/requiredRoles 와 값이 같아야 한다. 새 경로를 추가하면 여기와
     * {@code WebConfig.addPathPatterns} 둘 다 등록할 것.
     */
    private static final Map<String, String[]> PATH_ROLES = Map.of(
            "/api/admin/members", new String[] { "MEMBER_MANAGER", "SYSTEM_ADMIN" });

    private final AdminJwtVerifier verifier;

    public AdminAuthInterceptor(AdminJwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String[] requiredRoles = resolveRequiredRoles(request.getRequestURI());
        String authHeader = request.getHeader("Authorization");
        String token = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring("Bearer ".length())
                : null;
        AdminPrincipal admin = verifier.verify(token);
        if (admin == null) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "admin only");
            return false;
        }
        if (!admin.hasAnyRole(requiredRoles)) {
            logger.warn("admin 요청 거부(역할 부족): {} {} by {} roles={}",
                    request.getMethod(), request.getRequestURI(), admin.email(), admin.roles());
            response.sendError(HttpStatus.FORBIDDEN.value(), "admin only");
            return false;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, admin);
        return true;
    }

    /** 가장 구체적으로(긴 접두사로) 매칭되는 역할 목록을 고른다. */
    private String[] resolveRequiredRoles(String uri) {
        return PATH_ROLES.entrySet().stream()
                .filter(e -> uri.equals(e.getKey()) || uri.startsWith(e.getKey() + "/"))
                .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
                .map(Map.Entry::getValue)
                // 인터셉터가 등록된 경로인데 PATH_ROLES 에 없으면 설정 누락이다 —
                // 통과시키지 않고 존재할 수 없는 역할을 요구해 항상 거부되게 한다.
                .orElse(new String[] { "__UNCONFIGURED__" });
    }
}
