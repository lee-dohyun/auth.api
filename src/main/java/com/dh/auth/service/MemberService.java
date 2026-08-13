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
}
