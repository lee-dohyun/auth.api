package com.dh.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.dh.auth.service.MemberGradeRecalculationService;

/**
 * 회원 등급 월 1회 재산정.
 *
 * <p><b>단일 인스턴스 전제다.</b> auth-api 를 2개 이상으로 늘리면 같은 시각에 중복 실행된다.
 * 등급 변경은 멱등이라(같은 입력이면 같은 결과) 데이터가 깨지지는 않지만 이력이 두 벌 쌓이고
 * order.api 를 두 번 부른다. replica 를 늘릴 때는 ShedLock 같은 분산 락을 먼저 붙일 것.
 *
 * <p>테스트 프로파일에서는 뜨지 않는다({@code member-grade.scheduler.enabled=false}).
 */
@Component
@ConditionalOnProperty(name = "member-grade.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class MemberGradeScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemberGradeScheduler.class);

    private final MemberGradeRecalculationService recalculationService;

    public MemberGradeScheduler(MemberGradeRecalculationService recalculationService) {
        this.recalculationService = recalculationService;
    }

    /** 매월 1일 04:00 KST — 주문이 가장 적은 시간대이고, 월초에 등급이 바뀌는 것이 사용자에게 이해하기 쉽다. */
    @Scheduled(cron = "0 0 4 1 * *", zone = "Asia/Seoul")
    public void recalculate() {
        try {
            var result = recalculationService.recalculateAll();
            log.info("[등급배치] 대상 {}명 중 {}명 변경", result.examined(), result.changed());
        } catch (Exception e) {
            // 배치 실패로 애플리케이션을 죽이지 않는다. 다음 달까지 기다릴 수 없으면
            // /internal/member-grades/recalculate 로 수동 재실행할 수 있다.
            log.error("[등급배치] 재산정 실패 — 등급은 이전 상태 그대로다. 수동 재실행이 필요하다.", e);
        }
    }
}
