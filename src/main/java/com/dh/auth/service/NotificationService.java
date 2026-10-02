package com.dh.auth.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.auth.entity.Member;
import com.dh.auth.entity.Notification;
import com.dh.auth.repository.MemberRepository;
import com.dh.auth.repository.NotificationRepository;

/**
 * 회원 알림함(gateway#180). 등록은 클러스터 내부 서비스가, 조회·읽음 처리는 회원 본인이 한다.
 *
 * <p>클래스 레벨 {@code @Transactional(readOnly = true)} 를 걸지 않는다 — 쓰기 메서드가 섞여 있는
 * 클래스에 걸면 전파 함정으로 UPDATE 가 조용히 사라진다(캐논 §트랜잭션).
 */
@Service
public class NotificationService {

    /** 알림함이 한 번에 보여 주는 최대 건수. 헤더 드롭다운용이라 페이지를 넘기지 않는다. */
    public static final int MAX_LIST_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final MemberRepository memberRepository;

    public NotificationService(NotificationRepository notificationRepository, MemberRepository memberRepository) {
        this.notificationRepository = notificationRepository;
        this.memberRepository = memberRepository;
    }

    public enum CreateResult { CREATED, DUPLICATE, MEMBER_NOT_FOUND }

    public record Inbox(long unreadCount, List<Notification> items) {
    }

    /**
     * 알림 등록. 같은 회원에게 같은 dedupKey 가 이미 있으면 아무것도 만들지 않는다.
     *
     * <p>동시에 두 요청이 들어와 둘 다 "없음"을 본 경우는 UNIQUE(member_id, dedup_key) 가 막고,
     * 호출부(컨트롤러)가 그 제약 위반을 DUPLICATE 로 바꾼다 — 여기서 잡으면 이미 깨진 트랜잭션 안이다.
     */
    @Transactional
    public CreateResult create(
            String keycloakUserId, String type, String title, String body, String linkUrl, String dedupKey) {
        Optional<Member> member = memberRepository.findByKeycloakUserId(keycloakUserId);
        if (member.isEmpty()) {
            return CreateResult.MEMBER_NOT_FOUND;
        }
        Long memberId = member.get().getId();
        if (notificationRepository.existsByMemberIdAndDedupKey(memberId, dedupKey)) {
            return CreateResult.DUPLICATE;
        }
        notificationRepository.saveAndFlush(new Notification(memberId, type, title, body, linkUrl, dedupKey));
        return CreateResult.CREATED;
    }

    /** 최신순 알림과 안 읽은 개수. 로컬 회원 행이 없는 sub 는 empty. */
    @Transactional(readOnly = true)
    public Optional<Inbox> inbox(String keycloakUserId, int size) {
        int limit = Math.max(1, Math.min(size, MAX_LIST_SIZE));
        return memberRepository.findByKeycloakUserId(keycloakUserId).map(member -> new Inbox(
                notificationRepository.countByMemberIdAndReadAtIsNull(member.getId()),
                notificationRepository.findByMemberIdOrderByCreatedAtDescIdDesc(
                        member.getId(), PageRequest.of(0, limit))));
    }

    /** 헤더 배지용. 회원 행이 없으면 empty. */
    @Transactional(readOnly = true)
    public Optional<Long> unreadCount(String keycloakUserId) {
        return memberRepository.findByKeycloakUserId(keycloakUserId)
                .map(member -> notificationRepository.countByMemberIdAndReadAtIsNull(member.getId()));
    }

    /**
     * 한 건 읽음 처리. 내 알림이 아니거나 없는 id 면 false — 호출부가 404 로 응답한다
     * (403 은 그 id 가 존재한다는 사실을 드러낸다). 이미 읽은 알림은 true(재호출 멱등).
     */
    @Transactional
    public boolean markRead(String keycloakUserId, UUID publicId) {
        Optional<Member> member = memberRepository.findByKeycloakUserId(keycloakUserId);
        if (member.isEmpty()) {
            return false;
        }
        Long memberId = member.get().getId();
        if (notificationRepository.markRead(publicId, memberId, LocalDateTime.now()) > 0) {
            return true;
        }
        return notificationRepository.existsByPublicIdAndMemberId(publicId, memberId);
    }

    /** 전부 읽음 처리. 바뀐 건수를 돌려준다(회원 행이 없으면 0). */
    @Transactional
    public int markAllRead(String keycloakUserId) {
        return memberRepository.findByKeycloakUserId(keycloakUserId)
                .map(member -> notificationRepository.markAllRead(member.getId(), LocalDateTime.now()))
                .orElse(0);
    }
}
