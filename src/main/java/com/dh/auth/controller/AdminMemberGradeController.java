package com.dh.auth.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.auth.config.AdminAuthInterceptor;
import com.dh.auth.config.AdminPrincipal;
import com.dh.auth.dto.AdminMemberGradeDtos.GradeAdjustRequest;
import com.dh.auth.dto.AdminMemberGradeDtos.GradeAdjustResponse;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicy;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicyCreateRequest;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicyCreateResult;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicyListResponse;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicyUpdateRequest;
import com.dh.auth.dto.AdminMemberGradeDtos.MemberGradeDetail;
import com.dh.auth.service.AdminMemberGradeException;
import com.dh.auth.service.AdminMemberGradeService;

/**
 * 관리자 등급 관리 API — 등급 정책 CRUD, 회원 등급 조회·수동 조정 (gateway#80).
 *
 * <p>인증·인가는 {@link AdminAuthInterceptor} 가 담당한다({@code MEMBER_MANAGER} 또는
 * {@code SYSTEM_ADMIN}). 경로가 두 갈래다:
 * <ul>
 *   <li>{@code /api/admin/member-grades/**} — 등급 정책. 인터셉터 {@code PATH_ROLES} 에 따로 등록돼 있다</li>
 *   <li>{@code /api/admin/members/{sub}/grade}(조회·수동 조정), {@code .../grade/lock}(고정 해제) — 회원 등급.
 *       {@code /api/admin/members} 규칙을 그대로 탄다</li>
 * </ul>
 * 경로를 바꾸면 {@code PATH_ROLES} 와 admin.front {@code lib/menu.ts} 의 apiPrefixes 도 같이 고칠 것.
 *
 * <p>호출자는 admin.front 의 Route Handler 이며 클러스터 내부에서 직접 들어온다(게이트웨이 미경유).
 * 규칙과 정기 재산정과의 관계는 {@link AdminMemberGradeService} 주석 참고.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminMemberGradeController {

    private final AdminMemberGradeService service;

    public AdminMemberGradeController(AdminMemberGradeService service) {
        this.service = service;
    }

    @GetMapping("/member-grades")
    public GradePolicyListResponse listPolicies() {
        return new GradePolicyListResponse(service.listPolicies());
    }

    /** 새로 만들면 201, 같은 내용이 이미 있으면(재시도) 200. */
    @PostMapping("/member-grades")
    public ResponseEntity<GradePolicy> createPolicy(@RequestBody GradePolicyCreateRequest request) {
        GradePolicyCreateResult result = service.createPolicy(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.policy());
    }

    @PutMapping("/member-grades/{code}")
    public GradePolicy updatePolicy(@PathVariable String code, @RequestBody GradePolicyUpdateRequest request) {
        return service.updatePolicy(code, request);
    }

    @DeleteMapping("/member-grades/{code}")
    public ResponseEntity<Void> deletePolicy(@PathVariable String code) {
        service.deletePolicy(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/members/{keycloakUserId}/grade")
    public MemberGradeDetail memberGrade(@PathVariable String keycloakUserId) {
        return service.getMemberGrade(keycloakUserId);
    }

    @PutMapping("/members/{keycloakUserId}/grade")
    public GradeAdjustResponse adjustMemberGrade(
            @PathVariable String keycloakUserId,
            @RequestBody GradeAdjustRequest request,
            @RequestAttribute(name = AdminAuthInterceptor.PRINCIPAL_ATTRIBUTE, required = false)
            AdminPrincipal admin) {
        return service.adjustMemberGrade(keycloakUserId, request, admin == null ? "unknown" : admin.email());
    }

    /** 등급 고정 해제(auth.api#49). 등급은 그대로 두고 다음 정기 재산정부터 다시 계산되게 한다. */
    @DeleteMapping("/members/{keycloakUserId}/grade/lock")
    public GradeAdjustResponse releaseGradeLock(
            @PathVariable String keycloakUserId,
            @RequestAttribute(name = AdminAuthInterceptor.PRINCIPAL_ATTRIBUTE, required = false)
            AdminPrincipal admin) {
        return service.releaseGradeLock(keycloakUserId, admin == null ? "unknown" : admin.email());
    }

    /** 거부 사유는 {@code {"error": 코드, "message": 설명}} 으로 돌려준다 — 화면이 코드로 문구를 고른다. */
    @ExceptionHandler(AdminMemberGradeException.class)
    public ResponseEntity<Map<String, String>> handle(AdminMemberGradeException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getError(), "message", e.getMessage()));
    }
}
