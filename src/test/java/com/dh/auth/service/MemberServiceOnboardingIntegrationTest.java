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
import com.dh.auth.entity.PhoneVerification;
import com.dh.auth.repository.MemberRepository;
import com.dh.auth.repository.PhoneVerificationRepository;
import com.dh.auth.service.MemberService.OnboardingResult;

/**
 * auth.api#42 — 소셜 로그인 회원의 온보딩이 실제 DB 상태를 어떻게 바꾸는지 실 Postgres 에서 검증한다.
 *
 * <p>멱등성(재호출이 값을 덮어쓰지 않는가)과 1인 1번호 UNIQUE 와의 충돌은 목으로 답할 수 없는 질문이라
 * 실물 리포지토리·트랜잭션 매니저로 본다(캐논 §3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class MemberServiceOnboardingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PhoneVerificationRepository verificationRepository;

    private static String sub() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("소셜 로그인으로 만든 회원은 온보딩이 필요하고, 완료하면 전화번호·마케팅 동의·인증 이력이 연결된다")
    void 온보딩_완료() {
        String sub = sub();
        String phone = "+821055550001";
        memberService.createMemberForSocialLogin(sub);
        assertThat(memberService.isOnboardingRequired(sub)).contains(true);

        verificationRepository.save(new PhoneVerification(null, phone));
        assertThat(memberService.completeSocialOnboarding(sub, phone, true)).isEqualTo(OnboardingResult.COMPLETED);

        Member member = memberRepository.findByKeycloakUserId(sub).orElseThrow();
        assertThat(member.getCurrentPhoneNumber()).isEqualTo(phone);
        assertThat(member.isMarketingOptIn()).isTrue();
        assertThat(member.getMarketingOptInAt()).isNotNull();
        assertThat(memberService.isOnboardingRequired(sub)).contains(false);
        assertThat(verificationRepository.findTopByPhoneNumberAndMemberIsNullOrderByVerifiedAtDesc(phone))
                .as("인증 이력이 회원에 연결되면 '미연결' 조회에서는 더 이상 나오지 않는다")
                .isEmpty();
    }

    @Test
    @DisplayName("이미 온보딩을 마친 회원이 다른 번호로 다시 호출해도 기존 번호를 덮어쓰지 않는다")
    void 재호출은_덮어쓰지_않는다() {
        String sub = sub();
        memberService.createMemberForSocialLogin(sub);
        assertThat(memberService.completeSocialOnboarding(sub, "+821055550002", false))
                .isEqualTo(OnboardingResult.COMPLETED);

        assertThat(memberService.completeSocialOnboarding(sub, "+821055550003", true))
                .isEqualTo(OnboardingResult.ALREADY_COMPLETED);

        Member member = memberRepository.findByKeycloakUserId(sub).orElseThrow();
        assertThat(member.getCurrentPhoneNumber()).isEqualTo("+821055550002");
        assertThat(member.isMarketingOptIn()).isFalse();
    }

    @Test
    @DisplayName("다른 회원이 쓰는 번호로는 온보딩할 수 없고 회원은 그대로 온보딩 필요 상태로 남는다")
    void 번호_중복() {
        String owner = sub();
        String phone = "+821055550004";
        memberService.createMemberForSignup(owner, phone, false);

        String social = sub();
        memberService.createMemberForSocialLogin(social);
        assertThat(memberService.completeSocialOnboarding(social, phone, false))
                .isEqualTo(OnboardingResult.PHONE_IN_USE);
        assertThat(memberService.isOnboardingRequired(social)).contains(true);
    }

    @Test
    @DisplayName("회원 행이 없는 sub 는 MEMBER_NOT_FOUND / 조회는 empty")
    void 회원_없음() {
        String sub = sub();
        assertThat(memberService.isOnboardingRequired(sub)).isEmpty();
        assertThat(memberService.completeSocialOnboarding(sub, "+821055550005", false))
                .isEqualTo(OnboardingResult.MEMBER_NOT_FOUND);
    }
}
