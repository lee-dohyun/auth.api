package com.dh.auth.dto;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.dh.auth.entity.Notification;
import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    /**
     * 내부 서비스가 보내는 알림 등록 요청. 길이 제한은 notifications 컬럼 길이와 같다 —
     * 여기서 안 막으면 DB 가 500 으로 막는다.
     */
    public record CreateNotificationRequest(
            /** 받는 회원의 Keycloak sub. 이메일로 받지 않는다(변경 가능한 값이라 소유자 키가 아니다). */
            @NotBlank @Size(max = 36) String userId,
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String type,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 1000) String body,
            /** 눌렀을 때 갈 곳. https 절대 URL 만 받는다 — 헤더가 그대로 href 에 넣는다. */
            @Size(max = 500) @Pattern(regexp = "https://[^\\s]+") String linkUrl,
            @NotBlank @Size(max = 120) String dedupKey) {
    }

    /**
     * createdAt 은 오프셋을 붙여 내보낸다. 저장된 값은 서버 시간대의 LocalDateTime 인데 운영 파드는 UTC 다 —
     * 오프셋 없이 내보내면 브라우저가 자기 시간대(KST)로 읽어, 방금 온 알림이 헤더에 "9시간 전" 으로 찍힌다.
     */
    public record NotificationResponse(
            UUID id, String type, String title, String body, String linkUrl, boolean read,
            @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime createdAt) {

        public static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getPublicId(), n.getType(), n.getTitle(), n.getBody(), n.getLinkUrl(),
                    n.getReadAt() != null, n.getCreatedAt().atZone(ZoneId.systemDefault()).toOffsetDateTime());
        }
    }

    public record InboxResponse(long unreadCount, List<NotificationResponse> items) {
    }

    public record UnreadCountResponse(long unreadCount) {
    }
}
