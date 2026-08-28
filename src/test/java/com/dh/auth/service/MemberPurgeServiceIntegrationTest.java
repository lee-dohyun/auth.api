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

import com.dh.auth.entity.Member;
import com.dh.auth.entity.MemberAddress;
import com.dh.auth.entity.MemberGrade;
import com.dh.auth.entity.MemberGradeHistory;
import com.dh.auth.entity.PhoneVerification;
import com.dh.auth.repository.MemberAddressRepository;
import com.dh.auth.repository.MemberGradeHistoryRepository;
import com.dh.auth.repository.MemberGradeRepository;
import com.dh.auth.repository.MemberRepository;
import com.dh.auth.repository.PhoneVerificationRepository;

/**
 * 개인정보 파기의 실제 DB 상태 변화를 검증한다 (auth.api#40).
 *
 * <p><b>단위 테스트로는 성립하지 않는 검증이다.</b> 캐논이 못박은 대로 리포지토리를 목킹하면
 * "지웠다고 호출했다"만 확인될 뿐, FK 제약 때문에 삭제 순서가 틀렸는지 / 자식 행이 실제로
 * 사라졌는지는 알 수 없다. 그래서 Testcontainers 로 실제 Postgres 에 Flyway 를 적용해 돌린다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 <b>붙이지 않는다.</b> 붙이면 파기가 테스트 트랜잭션에
 * 합류해 롤백되므로 "정말 커밋됐는지"를 검증할 수 없다 — 대신 각 테스트가 자기 데이터를 직접 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class MemberPurgeServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Autowired
    private MemberPurgeService memberPurgeService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberGradeRepository memberGradeRepository;

    @Autowired
    private MemberAddressRepository memberAddressRepository;

    @Autowired
    private MemberGradeHistoryRepository memberGradeHistoryRepository;

    @Autowired
    private PhoneVerificationRepository phoneVerificationRepository;

    @Test
    @DisplayName("파기하면 회원과 자식 행(배송지·등급이력·전화번호 인증)이 실제 DB에서 모두 사라진다")
    void 파기는_자식_행까지_전부_지운다() {
        String keycloakUserId = UUID.randomUUID().toString();
        Member member = givenMemberWithPersonalData(keycloakUserId, "+821012345678");
        Long memberId = member.getId();

        MemberPurgeService.PurgeResult result = memberPurgeService.purgeLocalData(keycloakUserId);

        assertThat(result.memberExisted()).isTrue();
        assertThat(result.addresses()).isEqualTo(1);
        assertThat(result.gradeHistories()).isEqualTo(1);
        assertThat(result.phoneVerifications()).isEqualTo(1);

        // 호출 횟수가 아니라 실제 잔존 행으로 확인한다.
        assertThat(memberRepository.findByKeycloakUserId(keycloakUserId)).isEmpty();
        assertThat(memberAddressRepository.findByMemberIdOrderByIsDefaultDescCreatedAtDesc(memberId)).isEmpty();
        assertThat(phoneVerificationRepository.findByMemberId(memberId)).isEmpty();
    }

    @Test
    @DisplayName("로컬에 회원이 없어도 예외 없이 memberExisted=false로 끝난다 (Keycloak 좀비 계정 삭제 경로)")
    void 로컬에_없는_회원은_조용히_통과한다() {
        MemberPurgeService.PurgeResult result = memberPurgeService.purgeLocalData(UUID.randomUUID().toString());

        assertThat(result.memberExisted()).isFalse();
        assertThat(result.addresses()).isZero();
    }

    @Test
    @DisplayName("두 번 파기해도 실패하지 않는다 (멱등)")
    void 파기는_멱등이다() {
        String keycloakUserId = UUID.randomUUID().toString();
        givenMemberWithPersonalData(keycloakUserId, "+821098765432");

        MemberPurgeService.PurgeResult first = memberPurgeService.purgeLocalData(keycloakUserId);
        MemberPurgeService.PurgeResult second = memberPurgeService.purgeLocalData(keycloakUserId);

        assertThat(first.memberExisted()).isTrue();
        assertThat(second.memberExisted()).isFalse();
    }

    /**
     * 배송지 1건 + 등급이력 1건 + 전화번호 인증 1건을 가진 회원을 실제로 저장한다.
     *
     * @param phoneNumber <b>E.164 정규형이어야 한다</b>(V5 마이그레이션의 CHECK 제약).
     *                    {@code 010...} 형식으로 넣으면 제약 위반으로 시딩이 실패한다.
     */
    private Member givenMemberWithPersonalData(String keycloakUserId, String phoneNumber) {
        MemberGrade grade = memberGradeRepository.findByIsDefaultTrue().orElseThrow();
        Member member = memberRepository.saveAndFlush(new Member(keycloakUserId, grade));

        memberAddressRepository.saveAndFlush(new MemberAddress(
                member, "집", "홍길동", phoneNumber, "06234", "서울시 강남구", "101동 202호", true));
        memberGradeHistoryRepository.saveAndFlush(new MemberGradeHistory(member, grade, "테스트 시딩"));
        phoneVerificationRepository.saveAndFlush(new PhoneVerification(member, phoneNumber));

        return member;
    }
}
