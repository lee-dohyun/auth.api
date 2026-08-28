package com.dh.auth.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dh.auth.config.AdminAuthInterceptor;
import com.dh.auth.config.AdminPrincipal;
import com.dh.auth.dto.AdminMemberDtos.MemberDeleteResponse;
import com.dh.auth.dto.AdminMemberDtos.MemberListResponse;
import com.dh.auth.dto.AdminMemberDtos.MemberSummary;
import com.dh.auth.entity.Member;
import com.dh.auth.repository.MemberRepository;
import com.dh.auth.security.KeycloakClient;
import com.dh.auth.service.MemberPurgeService;

/**
 * 관리자 회원 관리 API.
 *
 * <p>인증·인가는 {@link AdminAuthInterceptor} 가 담당한다({@code MEMBER_MANAGER} 또는
 * {@code SYSTEM_ADMIN}). 이 컨트롤러는 인터셉터를 통과한 요청만 받는다는 전제로 쓰여 있으므로,
 * 경로를 바꾸면 인터셉터의 {@code PATH_ROLES} 와 {@code WebConfig} 등록도 같이 고쳐야 한다.
 *
 * <p>호출자는 admin.front 의 서버사이드(Next.js Route Handler)이며 클러스터 내부에서
 * 직접 들어온다 — 게이트웨이를 거치지 않으므로 {@code X-User-*} 헤더가 없다.
 */
@RestController
@RequestMapping("/api/admin/members")
public class AdminMemberController {

    private static final Logger log = LoggerFactory.getLogger(AdminMemberController.class);

    private final KeycloakClient keycloakClient;
    private final MemberRepository memberRepository;
    private final MemberPurgeService memberPurgeService;

    public AdminMemberController(
            KeycloakClient keycloakClient,
            MemberRepository memberRepository,
            MemberPurgeService memberPurgeService) {
        this.keycloakClient = keycloakClient;
        this.memberRepository = memberRepository;
        this.memberPurgeService = memberPurgeService;
    }

    /**
     * 회원 목록. Keycloak 을 기준으로 나열하고 로컬 members 정보를 덧붙인다
     * (좀비 계정도 보여야 하므로 — {@link KeycloakClient#listUsers} 주석 참고).
     */
    @GetMapping
    public MemberListResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search) {
        int safeSize = Math.clamp(size, 1, 100);
        int first = Math.max(page, 0) * safeSize;

        List<KeycloakClient.AdminUserSummary> users = keycloakClient.listUsers(first, safeSize, search);
        List<MemberSummary> items = users.stream().map(this::toSummary).toList();
        return new MemberListResponse(items, keycloakClient.countUsers(search), Math.max(page, 0), safeSize);
    }

    /**
     * 회원 파기 — 로컬 개인정보를 지우고 Keycloak 계정을 삭제한다.
     *
     * <p><b>순서가 중요하다.</b> 로컬 파기를 먼저 하고 Keycloak 을 나중에 지운다. 반대로 하면
     * Keycloak 이 지워진 뒤 로컬 파기가 실패했을 때 남은 개인정보를 찾아낼 신원 정보가 사라진다.
     * 로컬 파기는 트랜잭션이고 Keycloak 호출은 그 <b>바깥</b>이다(캐논: 트랜잭션 안에서 원격 HTTP 금지).
     *
     * <p>주문 이력(order.api)은 전자상거래법상 보존 대상이라 건드리지 않는다.
     */
    @DeleteMapping("/{keycloakUserId}")
    public ResponseEntity<MemberDeleteResponse> delete(
            @PathVariable String keycloakUserId,
            @RequestAttribute(name = AdminAuthInterceptor.PRINCIPAL_ATTRIBUTE, required = false)
            AdminPrincipal admin) {

        Optional<KeycloakClient.AdminUserSummary> target = keycloakClient.findUserById(keycloakUserId);
        boolean localOnly = target.isEmpty();

        MemberPurgeService.PurgeResult purged = memberPurgeService.purgeLocalData(keycloakUserId);

        // Keycloak 에도 없고 로컬에도 없으면 지울 대상 자체가 없었다는 뜻이다.
        if (localOnly && !purged.memberExisted()) {
            return ResponseEntity.notFound().build();
        }

        boolean keycloakDeleted = !localOnly && keycloakClient.deleteUserById(keycloakUserId);

        log.info("관리자 회원 파기: sub={} by={} keycloakDeleted={} local(addr={}, grade={}, phone={})",
                keycloakUserId,
                admin == null ? "unknown" : admin.email(),
                keycloakDeleted,
                purged.addresses(),
                purged.gradeHistories(),
                purged.phoneVerifications());

        return ResponseEntity.ok(new MemberDeleteResponse(
                keycloakUserId,
                keycloakDeleted,
                purged.memberExisted(),
                purged.addresses(),
                purged.gradeHistories(),
                purged.phoneVerifications()));
    }

    private MemberSummary toSummary(KeycloakClient.AdminUserSummary user) {
        Optional<Member> local = memberRepository.findByKeycloakUserId(user.id());
        return new MemberSummary(
                user.id(),
                user.email(),
                user.name(),
                user.emailVerified(),
                user.enabled(),
                local.isPresent(),
                local.map(m -> m.getCurrentGrade().getName()).orElse(null),
                local.map(Member::getCreatedAt).orElse(null),
                toLocalDateTime(user.createdTimestamp()));
    }

    private static LocalDateTime toLocalDateTime(Long epochMillis) {
        return epochMillis == null
                ? null
                : Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime();
    }
}
