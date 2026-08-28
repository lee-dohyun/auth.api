package com.dh.auth.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.auth.entity.Member;
import com.dh.auth.repository.MemberAddressRepository;
import com.dh.auth.repository.MemberGradeHistoryRepository;
import com.dh.auth.repository.MemberRepository;
import com.dh.auth.repository.PhoneVerificationRepository;

/**
 * 회원 개인정보 파기.
 *
 * <h2>왜 soft delete 가 아닌가</h2>
 * 개인정보보호법 제21조는 보유목적을 달성한 개인정보의 지체 없는 파기를 요구한다.
 * {@code withdrawn_at} 만 세팅하는 방식은 {@code member_addresses} 의 수령인명·연락처·주소를
 * 그대로 남기므로 파기로 볼 수 없다.
 *
 * <h2>거래기록은 왜 안 지우는가</h2>
 * 전자상거래법 시행령 제6조가 계약·결제 기록의 5년 보존을 요구한다. 주문은 order.api 의
 * 별도 DB에 있고 주문 시점 정보를 스냅샷으로 들고 있으며 이 DB와 <b>물리적 FK 가 없다</b>.
 * 따라서 여기서 회원을 파기해도 거래기록은 독립적으로 남아 보존의무를 위반하지 않는다.
 * <b>order.api 의 데이터를 여기서 건드리지 말 것.</b>
 */
@Service
public class MemberPurgeService {

    private final MemberRepository memberRepository;
    private final MemberAddressRepository memberAddressRepository;
    private final MemberGradeHistoryRepository memberGradeHistoryRepository;
    private final PhoneVerificationRepository phoneVerificationRepository;

    public MemberPurgeService(
            MemberRepository memberRepository,
            MemberAddressRepository memberAddressRepository,
            MemberGradeHistoryRepository memberGradeHistoryRepository,
            PhoneVerificationRepository phoneVerificationRepository) {
        this.memberRepository = memberRepository;
        this.memberAddressRepository = memberAddressRepository;
        this.memberGradeHistoryRepository = memberGradeHistoryRepository;
        this.phoneVerificationRepository = phoneVerificationRepository;
    }

    /** 파기 결과 — 호출부가 감사 로그에 남길 수 있도록 지운 행 수를 돌려준다. */
    public record PurgeResult(boolean memberExisted, int addresses, int gradeHistories, int phoneVerifications) {
    }

    /**
     * 로컬 회원 도메인 데이터를 파기한다. <b>Keycloak 계정은 지우지 않는다</b> —
     * 캐논상 {@code @Transactional} 안에서 원격 HTTP 를 부르면 로컬이 롤백돼도 원격은
     * 되돌아오지 않기 때문이다. 호출부가 이 메서드가 끝난 뒤 Keycloak 삭제를 이어서 한다.
     *
     * <p>회원이 로컬 DB 에 없어도 예외를 던지지 않는다. Keycloak 에만 있고 로컬에 연동되지 않은
     * "좀비" 계정이 실제로 존재하며(회원가입 중간 실패), 그런 계정도 삭제할 수 있어야 한다.
     *
     * @return 무엇을 몇 건 지웠는지. {@code memberExisted=false} 면 로컬에 아무것도 없었다는 뜻.
     */
    @Transactional
    public PurgeResult purgeLocalData(String keycloakUserId) {
        Optional<Member> found = memberRepository.findByKeycloakUserId(keycloakUserId);
        if (found.isEmpty()) {
            return new PurgeResult(false, 0, 0, 0);
        }
        Member member = found.get();
        Long memberId = member.getId();

        // 자식 → 부모 순서로 지운다. member_grade_history.member_id 와
        // member_addresses.member_id 는 NOT NULL FK 라 회원 행이 먼저 사라지면 제약 위반이 된다.
        int addresses = memberAddressRepository.deleteByMemberId(memberId);
        int gradeHistories = memberGradeHistoryRepository.deleteByMemberId(memberId);
        int phoneVerifications = phoneVerificationRepository.deleteByMemberId(memberId);
        memberRepository.delete(member);

        return new PurgeResult(true, addresses, gradeHistories, phoneVerifications);
    }
}
