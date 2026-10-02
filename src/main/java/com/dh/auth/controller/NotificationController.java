package com.dh.auth.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dh.auth.dto.NotificationDtos.InboxResponse;
import com.dh.auth.dto.NotificationDtos.NotificationResponse;
import com.dh.auth.dto.NotificationDtos.UnreadCountResponse;
import com.dh.auth.service.NotificationService;

/**
 * 내 알림함 — 공통 헤더(posselect-shell)의 알림 버튼이 부른다(gateway#180, posselect-shell#79).
 *
 * <p>회원은 게이트웨이가 주입한 {@code X-User-Id}(Keycloak sub)로만 식별한다. 경로·쿼리로 회원을 받지
 * 않으므로 남의 알림함을 열 방법이 없고, 읽음 처리도 "내 알림 중 그 id" 로만 찾는다.
 *
 * <p>헤더는 로그인 전 화면에도 떠 있지만 이 경로는 로그인 상태에서만 부른다(헤더가 /api/auth/me 성공 뒤에만
 * 호출). 그래서 gateway {@code PUBLIC_EXACT_PATHS} 대상이 아니다 — 대신 home 호스트에서도 X-User-Id 가
 * 주입되도록 gateway 의 선택 인증 경로에 등록돼 있어야 한다(없으면 home 에서 항상 401).
 */
@RestController
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/api/auth/notifications")
    public ResponseEntity<InboxResponse> inbox(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(defaultValue = "20") int size) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return notificationService.inbox(userId, size)
                .map(inbox -> ResponseEntity.ok(new InboxResponse(
                        inbox.unreadCount(), inbox.items().stream().map(NotificationResponse::from).toList())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 배지 숫자만. 헤더가 주기적으로 부르므로 목록을 싣지 않는다. */
    @GetMapping("/api/auth/notifications/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return notificationService.unreadCount(userId)
                .map(count -> ResponseEntity.ok(new UnreadCountResponse(count)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 한 건 읽음. 내 알림이 아니면 403 이 아니라 404 — 그 id 의 존재를 드러내지 않는다. */
    @PostMapping("/api/auth/notifications/{id}/read")
    public ResponseEntity<Void> markRead(
            @RequestHeader(value = "X-User-Id", required = false) String userId, @PathVariable UUID id) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return notificationService.markRead(userId, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/api/auth/notifications/read-all")
    public ResponseEntity<Void> markAllRead(@RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        notificationService.markAllRead(userId);
        return ResponseEntity.noContent().build();
    }
}
