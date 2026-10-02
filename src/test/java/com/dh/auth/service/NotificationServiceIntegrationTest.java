package com.dh.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dh.auth.entity.Notification;
import com.dh.auth.repository.NotificationRepository;
import com.dh.auth.service.NotificationService.CreateResult;
import com.dh.auth.service.NotificationService.Inbox;

/**
 * gateway#180 — 알림함의 멱등 등록·소유자 격리·읽음 처리·회원 파기 연쇄를 실 Postgres 에서 검증한다.
 * 전부 DB 제약(UNIQUE, FK ON DELETE CASCADE)과 실제 UPDATE 건수에 기대는 동작이라 목으로는 답이 안 나온다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class NotificationServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberPurgeService memberPurgeService;

    private String newMember() {
        String sub = UUID.randomUUID().toString();
        memberService.createMemberForSocialLogin(sub);
        return sub;
    }

    private CreateResult create(String sub, String dedupKey) {
        return notificationService.create(sub, "ORDER_PAID", "결제 완료", "주문 #1", "https://customer.posselect.com/mypage", dedupKey);
    }

    @Test
    @DisplayName("같은 dedupKey 로 두 번 등록해도 알림은 한 건만 생긴다")
    void 등록은_멱등() {
        String sub = newMember();

        assertThat(create(sub, "order-paid:1")).isEqualTo(CreateResult.CREATED);
        assertThat(create(sub, "order-paid:1")).isEqualTo(CreateResult.DUPLICATE);

        Inbox inbox = notificationService.inbox(sub, 20).orElseThrow();
        assertThat(inbox.items()).hasSize(1);
        assertThat(inbox.unreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("dedupKey 는 회원 단위다 — 다른 회원은 같은 키로 각자 알림을 받는다")
    void dedup_은_회원_단위() {
        String a = newMember();
        String b = newMember();

        assertThat(create(a, "order-paid:7")).isEqualTo(CreateResult.CREATED);
        assertThat(create(b, "order-paid:7")).isEqualTo(CreateResult.CREATED);
    }

    @Test
    @DisplayName("로컬 회원 행이 없는 sub 는 MEMBER_NOT_FOUND, 조회는 empty")
    void 회원_없음() {
        String sub = UUID.randomUUID().toString();

        assertThat(create(sub, "k")).isEqualTo(CreateResult.MEMBER_NOT_FOUND);
        assertThat(notificationService.inbox(sub, 20)).isEmpty();
        assertThat(notificationService.unreadCount(sub)).isEmpty();
    }

    @Test
    @DisplayName("목록은 최신순이고 size 로 잘린다")
    void 목록_최신순() {
        String sub = newMember();
        create(sub, "k1");
        create(sub, "k2");
        create(sub, "k3");

        Inbox inbox = notificationService.inbox(sub, 2).orElseThrow();

        assertThat(inbox.items()).extracting(Notification::getDedupKey).containsExactly("k3", "k2");
        assertThat(inbox.unreadCount()).as("안 읽은 개수는 잘린 목록이 아니라 전체 기준").isEqualTo(3);
    }

    @Test
    @DisplayName("읽음 처리는 내 알림에만 통하고, 다시 호출해도 읽은 시각을 덮어쓰지 않는다")
    void 읽음_처리와_소유자_격리() {
        String owner = newMember();
        String other = newMember();
        create(owner, "k1");
        Notification mine = notificationService.inbox(owner, 20).orElseThrow().items().get(0);

        assertThat(notificationService.markRead(other, mine.getPublicId()))
                .as("남의 알림 id 로는 읽음 처리가 안 된다").isFalse();
        assertThat(notificationService.unreadCount(owner)).contains(1L);

        assertThat(notificationService.markRead(owner, mine.getPublicId())).isTrue();
        var firstReadAt = notificationRepository.findById(idOf(mine)).orElseThrow().getReadAt();
        assertThat(firstReadAt).isNotNull();
        assertThat(notificationService.unreadCount(owner)).contains(0L);

        assertThat(notificationService.markRead(owner, mine.getPublicId())).as("재호출도 성공으로 본다").isTrue();
        assertThat(notificationRepository.findById(idOf(mine)).orElseThrow().getReadAt()).isEqualTo(firstReadAt);

        assertThat(notificationService.markRead(owner, UUID.randomUUID())).as("없는 id").isFalse();
    }

    @Test
    @DisplayName("전체 읽음은 내 것만 바꾼다")
    void 전체_읽음() {
        String a = newMember();
        String b = newMember();
        create(a, "k1");
        create(a, "k2");
        create(b, "k1");

        assertThat(notificationService.markAllRead(a)).isEqualTo(2);

        assertThat(notificationService.unreadCount(a)).contains(0L);
        assertThat(notificationService.unreadCount(b)).contains(1L);
        assertThat(notificationService.markAllRead(a)).as("다시 불러도 바뀌는 건 없다").isZero();
    }

    @Test
    @DisplayName("회원을 파기하면 알림도 같이 사라진다")
    void 회원_파기_연쇄() {
        String sub = newMember();
        create(sub, "k1");
        Notification n = notificationService.inbox(sub, 20).orElseThrow().items().get(0);

        memberPurgeService.purgeLocalData(sub);

        assertThat(notificationRepository.existsById(idOf(n))).isFalse();
    }

    private Long idOf(Notification n) {
        return n.getId();
    }
}
