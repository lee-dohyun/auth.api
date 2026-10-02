package com.dh.auth.controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.dh.auth.dto.NotificationDtos.CreateNotificationRequest;
import com.dh.auth.service.NotificationService;
import com.dh.auth.service.NotificationService.CreateResult;

import jakarta.validation.Valid;

/**
 * 알림 등록 입구 — 클러스터 내부 서비스 전용이다(gateway#180). {@code /internal/**} 은 게이트웨이에 라우트가
 * 없어 외부에서 도달할 수 없다({@link InternalMemberGradeController} 와 같은 신뢰 경계).
 *
 * <p>같은 (회원, dedupKey) 로 다시 부르면 새로 만들지 않고 200 을 돌려준다. 호출하는 쪽이 타임아웃 뒤
 * 재시도해도 알림이 두 번 뜨지 않는다. 처음 만든 경우만 201 이다.
 */
@Validated
@RestController
public class InternalNotificationController {

    private final NotificationService notificationService;

    public InternalNotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/internal/notifications")
    public ResponseEntity<Void> create(@Valid @RequestBody CreateNotificationRequest request) {
        CreateResult result;
        try {
            result = notificationService.create(request.userId(), request.type(), request.title(), request.body(),
                    request.linkUrl(), request.dedupKey());
        } catch (DataIntegrityViolationException e) {
            // 동시 요청 둘 다 "없음"을 보고 넣으려던 경우 — UNIQUE(member_id, dedup_key) 가 하나를 막았다.
            // 다른 제약 위반이면 그대로 올려 500 이 되게 한다(진짜 버그를 "중복"으로 숨기지 않는다).
            if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_notifications_member_dedup")) {
                throw e;
            }
            result = CreateResult.DUPLICATE;
        }
        return switch (result) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).build();
            case DUPLICATE -> ResponseEntity.ok().build();
            // 로컬 회원 행이 없는 sub(게스트 주문 등). 호출자는 "알림 보낼 대상 없음"으로 처리한다.
            case MEMBER_NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }
}
