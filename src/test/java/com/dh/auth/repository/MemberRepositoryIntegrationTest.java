package com.dh.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dh.auth.entity.Member;
import com.dh.auth.entity.MemberGrade;

/**
 * Testcontainers 기반 실제 Postgres 통합 테스트 (auth.api#15, architecture#14 / posselect-shell#26 후속).
 *
 * <p><b>왜 필요한가.</b> 기존 5개 단위 테스트는 전부 Mockito로 리포지토리를 목킹하거나(서비스 계층)
 * H2 + {@code create-drop} 프로파일(`application-test.yml`)로 돌아 Flyway를 아예 타지 않는다. 즉
 * "엔티티와 Flyway 마이그레이션이 실제로 맞물려 동작하는지"는 지금까지 어떤 테스트도 검증한 적이
 * 없었다 — 이 저장소 CLAUDE.md의 "테스트는 마이그레이션 누락을 못 잡는다" 경고가 정확히 이 공백을
 * 가리킨다.
 *
 * <p>이 테스트는 실제 {@code postgres:16-alpine} 컨테이너 위에 V1~V7 마이그레이션을 그대로 적용한
 * 뒤(H2로 우회하지 않음), V1이 시딩한 기본 등급 데이터를 읽고 회원 엔티티를 저장/조회해
 * FK·유니크 제약·시퀀스가 실제 Postgres에서 기대대로 동작하는지 확인한다.
 */
// 클래스 레벨 @Transactional: currentGrade가 FetchType.LAZY라 지연 로딩을 검증하려면
// 트랜잭션이 열려 있어야 한다(open-in-view: false이므로 컨트롤러 밖에서는 자동으로 안 열림).
// 각 테스트 종료 시 자동 롤백되므로 별도 정리 코드 없이도 테스트 간 격리가 유지된다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional
class MemberRepositoryIntegrationTest {

    // 이 개발 머신은 세션이 10개 이상 동시에 떠 있는 게 정상이라 컨테이너 초기 기동이
    // 기본 타임아웃(60초)보다 오래 걸릴 때가 있다 - CI(ubuntu-latest, 전용 자원)는 문제 없지만
    // 로컬 재현성을 위해 여유를 둔다.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberGradeRepository memberGradeRepository;

    @Test
    @DisplayName("V1 마이그레이션이 시딩한 기본 등급(GENERAL)을 실제 Postgres에서 읽을 수 있다")
    void v1_마이그레이션이_기본_등급을_시딩한다() {
        MemberGrade defaultGrade = memberGradeRepository.findByIsDefaultTrue().orElseThrow();

        assertThat(defaultGrade.getCode()).isEqualTo("GENERAL");
        assertThat(defaultGrade.isDefault()).isTrue();
    }

    @Test
    @DisplayName("회원을 저장하면 실제 Postgres에 커밋되고 keycloakUserId로 다시 조회된다")
    void 회원_저장과_조회가_실제_DB에서_왕복한다() {
        MemberGrade generalGrade = memberGradeRepository.findByCode("GENERAL").orElseThrow();
        String keycloakUserId = UUID.randomUUID().toString();

        Member saved = memberRepository.saveAndFlush(new Member(keycloakUserId, generalGrade));

        assertThat(saved.getId()).isNotNull();

        Member found = memberRepository.findByKeycloakUserId(keycloakUserId).orElseThrow();
        assertThat(found.getCurrentGrade().getCode()).isEqualTo("GENERAL");
        assertThat(found.isMarketingOptIn()).isFalse();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("keycloak_user_id 유니크 제약이 실제 DB 레벨에서 중복 저장을 막는다")
    void keycloak_user_id_중복은_제약위반으로_거부된다() {
        MemberGrade generalGrade = memberGradeRepository.findByCode("GENERAL").orElseThrow();
        String keycloakUserId = UUID.randomUUID().toString();
        memberRepository.saveAndFlush(new Member(keycloakUserId, generalGrade));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> memberRepository.saveAndFlush(new Member(keycloakUserId, generalGrade)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
