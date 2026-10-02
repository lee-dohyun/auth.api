package com.dh.auth.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dh.auth.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId, Pageable pageable);

    long countByMemberIdAndReadAtIsNull(Long memberId);

    boolean existsByMemberIdAndDedupKey(Long memberId, String dedupKey);

    /**
     * 읽음 처리. 조건에 member_id 를 같이 건다 — 남의 알림 id 를 넣어도 0건이 되어 소유자 검사가
     * 조회와 갱신 사이에서 벌어질 틈이 없다. 이미 읽은 알림은 read_at 을 덮어쓰지 않는다(재호출 멱등).
     */
    @Modifying
    @Query("update Notification n set n.readAt = :now "
            + "where n.publicId = :publicId and n.memberId = :memberId and n.readAt is null")
    int markRead(@Param("publicId") UUID publicId, @Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    boolean existsByPublicIdAndMemberId(UUID publicId, Long memberId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.memberId = :memberId and n.readAt is null")
    int markAllRead(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);
}
