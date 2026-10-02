package com.dh.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.auth.entity.Member;
import com.dh.auth.entity.MemberGrade;
import com.dh.auth.entity.MemberGradeHistory;
import com.dh.auth.repository.MemberGradeHistoryRepository;
import com.dh.auth.repository.MemberGradeRepository;
import com.dh.auth.repository.MemberRepository;
import com.dh.auth.support.PhoneNumbers;

/** Keycloak 가입 완료 후 로컬 도메인 데이터(등급, 전화번호 연결)를 이어붙이는 서비스. */
@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final MemberGradeRepository memberGradeRepository;
    private final MemberGradeHistoryRepository memberGradeHistoryRepository;
    private final PhoneVerificationService phoneVerificationService;

    public MemberService(
            MemberRepository memberRepository,
            MemberGradeRepository memberGradeRepository,
            MemberGradeHistoryRepository memberGradeHistoryRepository,
            PhoneVerificationService phoneVerificationService) {
        this.memberRepository = memberRepository;
        this.memberGradeRepository = memberGradeRepository;
        this.memberGradeHistoryRepository = memberGradeHistoryRepository;
        this.phoneVerificationService = phoneVerificationService;
    }

    /**
     * 회원가입 완료 시 호출 — 기본 등급을 부여한 Member를 만들고, 방금 인증한 전화번호를 연결하고,
     * 선택 동의인 마케팅 수신 동의 여부를 기록한다.
     */
    @Transactional
    public Member createMemberForSignup(String keycloakUserId, String phoneNumber, boolean marketingOptIn) {
        MemberGrade defaultGrade = memberGradeRepository.findByIsDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("기본 등급이 설정되어 있지 않습니다."));

        Member member = new Member(keycloakUserId, defaultGrade);
        // PhoneVerificationService와 같은 정규형(E.164)을 써야 한다 — 여기서만 다르게 정규화하면
        // members.current_phone_number와 인증 이력이 서로 다른 표기로 갈라진다.
        member.changePhoneNumber(PhoneNumbers.requireE164(phoneNumber));
        member.changeMarketingOptIn(marketingOptIn);
        memberRepository.save(member);
        memberGradeHistoryRepository.save(new MemberGradeHistory(member, defaultGrade, "회원가입 기본 등급 부여"));
        phoneVerificationService.linkVerificationToMember(phoneNumber, member);

        return member;
    }

    /**
     * 소셜 로그인 최초 접속 시 로컬 도메인 데이터를 생성한다. 전화번호는 비워 둔다 —
     * 약관 동의와 휴대폰 인증은 로그인 직후 온보딩에서 받는다({@link #completeSocialOnboarding}).
     */
    @Transactional
    public Member createMemberForSocialLogin(String keycloakUserId) {
        MemberGrade defaultGrade = memberGradeRepository.findByIsDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("기본 등급이 설정되어 있지 않습니다."));

        Member member = new Member(keycloakUserId, defaultGrade);
        memberRepository.save(member);
        memberGradeHistoryRepository.save(new MemberGradeHistory(member, defaultGrade, "소셜 로그인 기본 등급 부여"));

        return member;
    }

    /** {@link #completeSocialOnboarding} 의 결과. */
    public enum OnboardingResult { COMPLETED, ALREADY_COMPLETED, MEMBER_NOT_FOUND, PHONE_IN_USE }

    /**
     * 온보딩(약관 동의 + 휴대폰 인증)이 남았는지. 회원 행이 없으면 empty.
     *
     * <p>판정 기준은 본인 인증 전화번호의 유무다 — 일반 가입은 전화번호 없이 성립하지 않으므로
     * 비어 있는 회원은 소셜 로그인으로 만들어져 온보딩을 안 거친 회원뿐이다(auth.api#42).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Boolean> isOnboardingRequired(String keycloakUserId) {
        return memberRepository.findByKeycloakUserId(keycloakUserId)
                .map(member -> member.getCurrentPhoneNumber() == null);
    }

    /**
     * 소셜 로그인 회원의 온보딩을 완료한다 — 방금 인증한 전화번호와 마케팅 수신 동의를 기록한다.
     * 호출 전에 "최근 인증된 번호인가"는 컨트롤러가 확인한다(signup 과 같은 순서).
     *
     * <p>이미 전화번호가 있는 회원은 건드리지 않는다(재호출 멱등). 계정 인증 번호는 1인 1번호
     * (uq_members_current_phone_number)라, 다른 회원이 쓰는 번호면 저장하지 않고 PHONE_IN_USE 를 돌려준다 —
     * 이메일로 이미 가입한 사람이 같은 번호로 소셜 계정을 하나 더 만드는 경우가 여기에 걸린다.
     */
    @Transactional
    public OnboardingResult completeSocialOnboarding(String keycloakUserId, String phoneNumber, boolean marketingOptIn) {
        Member member = memberRepository.findByKeycloakUserId(keycloakUserId).orElse(null);
        if (member == null) {
            return OnboardingResult.MEMBER_NOT_FOUND;
        }
        if (member.getCurrentPhoneNumber() != null) {
            return OnboardingResult.ALREADY_COMPLETED;
        }
        String normalized = PhoneNumbers.requireE164(phoneNumber);
        if (memberRepository.existsByCurrentPhoneNumber(normalized)) {
            return OnboardingResult.PHONE_IN_USE;
        }
        member.changePhoneNumber(normalized);
        member.changeMarketingOptIn(marketingOptIn);
        phoneVerificationService.linkVerificationToMember(phoneNumber, member);
        return OnboardingResult.COMPLETED;
    }

    @Transactional(readOnly = true)
    public boolean existsByKeycloakUserId(String keycloakUserId) {
        return memberRepository.findByKeycloakUserId(keycloakUserId).isPresent();
    }

    // withdrawMember(soft withdraw)는 제거됐다(auth.api#40). withdrawn_at 만 세팅하는 방식은
    // member_addresses 의 수령인명·연락처·주소를 남겨 개인정보 파기가 되지 않았다.
    // 탈퇴·관리자 삭제 모두 MemberPurgeService 를 쓴다.
}
