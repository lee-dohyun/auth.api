package com.dh.auth.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.auth.dto.AdminMemberGradeDtos.CurrentGrade;
import com.dh.auth.dto.AdminMemberGradeDtos.GradeAdjustRequest;
import com.dh.auth.dto.AdminMemberGradeDtos.GradeAdjustResponse;
import com.dh.auth.dto.AdminMemberGradeDtos.GradeHistoryItem;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicy;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicyCreateRequest;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicyCreateResult;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicyUpdateRequest;
import com.dh.auth.dto.AdminMemberGradeDtos.MemberGradeDetail;
import com.dh.auth.entity.Member;
import com.dh.auth.entity.MemberGrade;
import com.dh.auth.entity.MemberGradeHistory;
import com.dh.auth.repository.MemberGradeHistoryRepository;
import com.dh.auth.repository.MemberGradeRepository;
import com.dh.auth.repository.MemberRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * 관리자 등급 관리 — 등급 정책 CRUD, 회원 등급 조회·수동 조정 (gateway#80).
 *
 * <h2>정기 재산정과의 관계 (반드시 알고 쓸 것)</h2>
 * {@link MemberGradeRecalculationService} 는 매월 1일 전 회원을 구매확정액으로 다시 계산하고,
 * 결과가 현재 등급과 다르면 <b>수동 조정 여부와 무관하게 덮어쓴다.</b> 수동 조정을 고정(lock)하는
 * 장치는 없다 — 스키마에 그 표시를 담을 컬럼이 없고, 이 이슈 범위도 아니다. 수동 조정은 이력의
 * 사유가 {@value #MANUAL_REASON_PREFIX} 로 시작하는 것으로만 구분된다.
 *
 * <h2>트랜잭션</h2>
 * 클래스 레벨 {@code readOnly} 를 두지 않는다 — 쓰기 메서드가 읽기 전용 트랜잭션에 합류하면
 * UPDATE 가 조용히 사라진다(캐논 §3). 메서드마다 명시한다. 원격 호출은 없다.
 */
@Service
public class AdminMemberGradeService {

    private static final Logger log = LoggerFactory.getLogger(AdminMemberGradeService.class);

    /** 이력 사유 접두어. 화면(admin.front)과 사용자 설명서가 이 문구로 수동 조정을 알아본다. */
    public static final String MANUAL_REASON_PREFIX = "수동 조정: ";

    /** {@code member_grade_history.reason} 이 VARCHAR(100) 이라 접두어를 뺀 사유는 80자까지 받는다. */
    static final int MAX_REASON_LENGTH = 80;

    /** {@code member_grades.code} VARCHAR(20). 기존 코드(GENERAL/SILVER/GOLD/VIP)와 같은 모양만 받는다. */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,19}$");

    private static final BigDecimal MAX_DISCOUNT_RATE = new BigDecimal("100");
    /** NUMERIC(12,2) 의 정수부 10자리. */
    private static final BigDecimal MAX_SPEND_AMOUNT = new BigDecimal("9999999999.99");

    private final MemberRepository memberRepository;
    private final MemberGradeRepository memberGradeRepository;
    private final MemberGradeHistoryRepository memberGradeHistoryRepository;
    private final EntityManager entityManager;

    public AdminMemberGradeService(
            MemberRepository memberRepository,
            MemberGradeRepository memberGradeRepository,
            MemberGradeHistoryRepository memberGradeHistoryRepository,
            EntityManager entityManager) {
        this.memberRepository = memberRepository;
        this.memberGradeRepository = memberGradeRepository;
        this.memberGradeHistoryRepository = memberGradeHistoryRepository;
        this.entityManager = entityManager;
    }

    // ───────────── 등급 정책 ─────────────

    @Transactional(readOnly = true)
    public List<GradePolicy> listPolicies() {
        return memberGradeRepository.findAll().stream()
                .sorted(Comparator.comparing(MemberGrade::getSortOrder).thenComparing(MemberGrade::getCode))
                .map(AdminMemberGradeService::toPolicy)
                .toList();
    }

    /**
     * 등급 생성. <b>멱등</b>: 같은 코드·같은 내용이 이미 있으면 아무것도 만들지 않고 그 등급을 돌려준다
     * ({@code created=false}). 같은 코드에 내용이 다르면 409 — 덮어쓰려면 수정(PUT)을 써야 한다.
     *
     * <p>기준액을 넣은 등급은 다음 정기 재산정부터 자동 부여 대상이 된다.
     */
    @Transactional
    public GradePolicyCreateResult createPolicy(GradePolicyCreateRequest request) {
        String code = request.code() == null ? "" : request.code().trim();
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw badRequest("INVALID_CODE", "등급 코드는 영문 대문자로 시작하는 대문자·숫자·밑줄 20자 이하여야 합니다.");
        }
        String name = validateName(request.name());
        BigDecimal discountRate = validateDiscountRate(request.discountRate());
        BigDecimal minSpendAmount = validateMinSpendAmount(request.minSpendAmount());
        Integer sortOrder = validateSortOrder(request.sortOrder());

        Optional<MemberGrade> existing = memberGradeRepository.findByCode(code);
        if (existing.isPresent()) {
            MemberGrade grade = existing.get();
            if (samePolicy(grade, name, discountRate, minSpendAmount, sortOrder)) {
                return new GradePolicyCreateResult(toPolicy(grade), false);
            }
            throw new AdminMemberGradeException(HttpStatus.CONFLICT, "GRADE_CODE_CONFLICT",
                    "같은 코드의 등급이 이미 있습니다: " + code);
        }
        rejectDuplicateThreshold(code, minSpendAmount);

        try {
            MemberGrade saved = memberGradeRepository.saveAndFlush(
                    new MemberGrade(code, name, discountRate, minSpendAmount, sortOrder));
            log.info("등급 생성: code={} discountRate={} minSpendAmount={}", code, discountRate, minSpendAmount);
            return new GradePolicyCreateResult(toPolicy(saved), true);
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 코드로 생성 요청이 들어온 경우 — uq_member_grades_code 가 한쪽을 막는다.
            throw new AdminMemberGradeException(HttpStatus.CONFLICT, "GRADE_CODE_CONFLICT",
                    "같은 코드의 등급이 이미 있습니다: " + code);
        }
    }

    /**
     * 등급 정책 수정(이름·할인율·기준액·정렬 순서). 같은 값으로 다시 불러도 결과가 같다(멱등).
     *
     * <p><b>적용 시점이 항목마다 다르다.</b> 할인율은 저장 즉시 그 등급 회원의 다음 주문부터 적용되고
     * (order.api 가 주문 때마다 읽는다), 기준액은 다음 정기 재산정 때 회원 등급에 반영된다.
     */
    @Transactional
    public GradePolicy updatePolicy(String code, GradePolicyUpdateRequest request) {
        MemberGrade grade = memberGradeRepository.findByCode(code).orElseThrow(() -> gradeNotFound(code));
        String name = validateName(request.name());
        BigDecimal discountRate = validateDiscountRate(request.discountRate());
        BigDecimal minSpendAmount = validateMinSpendAmount(request.minSpendAmount());
        Integer sortOrder = validateSortOrder(request.sortOrder());

        // 기본 등급의 기준액이 0원이 아니면 재산정이 그 미만 회원을 어느 등급에도 넣지 못한다
        // (MemberGradeRecalculationService.resolveGrade 가 null). NULL 은 V8 CHECK 가 막는다.
        if (grade.isDefault() && (minSpendAmount == null || minSpendAmount.signum() != 0)) {
            throw badRequest("DEFAULT_GRADE_THRESHOLD_FIXED", "기본 등급의 기준액은 0원이어야 합니다.");
        }
        rejectDuplicateThreshold(code, minSpendAmount);

        grade.changePolicy(name, discountRate, minSpendAmount, sortOrder);
        log.info("등급 정책 수정: code={} discountRate={} minSpendAmount={}", code, discountRate, minSpendAmount);
        return toPolicy(grade);
    }

    /**
     * 등급 삭제. 기본 등급과, 회원 또는 이력이 참조하는 등급은 지울 수 없다(409).
     * 참조 여부는 세지 않고 DB 의 FK 제약에 맡긴다 — 세고 지우는 사이에 회원이 그 등급을 받을 수 있다.
     */
    @Transactional
    public void deletePolicy(String code) {
        MemberGrade grade = memberGradeRepository.findByCode(code).orElseThrow(() -> gradeNotFound(code));
        if (grade.isDefault()) {
            throw new AdminMemberGradeException(HttpStatus.CONFLICT, "DEFAULT_GRADE_UNDELETABLE",
                    "기본 등급은 삭제할 수 없습니다.");
        }
        try {
            memberGradeRepository.delete(grade);
            memberGradeRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new AdminMemberGradeException(HttpStatus.CONFLICT, "GRADE_IN_USE",
                    "이 등급인 회원이 있거나 등급 이력에 남아 있어 삭제할 수 없습니다: " + code);
        }
        log.info("등급 삭제: code={}", code);
    }

    // ───────────── 회원 등급 ─────────────

    @Transactional(readOnly = true)
    public MemberGradeDetail getMemberGrade(String keycloakUserId) {
        Member member = memberRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> memberNotFound(keycloakUserId));
        return toDetail(member);
    }

    /**
     * 회원 등급 수동 조정.
     *
     * <p><b>멱등</b>: 이미 그 등급이면 아무것도 바꾸지 않고 이력도 남기지 않는다({@code changed=false}).
     * 회원 행을 잠근 뒤 현재 등급을 다시 읽으므로, 같은 요청이 동시에 들어와도 이력은 한 건만 생긴다.
     *
     * <p><b>다음 정기 재산정에서 다시 계산된다</b> — 클래스 주석 참고.
     *
     * @param adminEmail 누가 했는지 로그에 남긴다. 이력 컬럼에는 담지 않는다(사유 100자 제한, 스키마 변경 없음)
     */
    @Transactional
    public GradeAdjustResponse adjustMemberGrade(String keycloakUserId, GradeAdjustRequest request, String adminEmail) {
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw badRequest("REASON_REQUIRED", "조정 사유는 필수입니다.");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw badRequest("REASON_TOO_LONG", "조정 사유는 " + MAX_REASON_LENGTH + "자 이하여야 합니다.");
        }
        if (request.gradeCode() == null || request.gradeCode().isBlank()) {
            throw badRequest("GRADE_CODE_REQUIRED", "등급 코드는 필수입니다.");
        }

        Member member = memberRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> memberNotFound(keycloakUserId));
        MemberGrade target = memberGradeRepository.findByCode(request.gradeCode())
                .orElseThrow(() -> gradeNotFound(request.gradeCode()));

        // SELECT ... FOR UPDATE 로 다시 읽는다. 잠금 없이 비교하면 동시 요청 둘 다 "아직 안 바뀜"으로
        // 보고 이력을 두 건 쌓는다.
        entityManager.refresh(member, LockModeType.PESSIMISTIC_WRITE);

        String fromCode = member.getCurrentGrade().getCode();
        if (fromCode.equals(target.getCode())) {
            return new GradeAdjustResponse(false, toDetail(member));
        }

        member.changeGrade(target);
        memberGradeHistoryRepository.saveAndFlush(
                new MemberGradeHistory(member, target, MANUAL_REASON_PREFIX + reason));
        log.info("회원 등급 수동 조정: sub={} {} -> {} by={}", keycloakUserId, fromCode, target.getCode(), adminEmail);
        return new GradeAdjustResponse(true, toDetail(member));
    }

    // ───────────── 내부 ─────────────

    private MemberGradeDetail toDetail(Member member) {
        MemberGrade grade = member.getCurrentGrade();
        List<GradeHistoryItem> history = memberGradeHistoryRepository.findWithGradeByMemberId(member.getId()).stream()
                .map(h -> new GradeHistoryItem(
                        h.getGrade().getCode(), h.getGrade().getName(), h.getReason(), h.getAssignedAt()))
                .toList();
        return new MemberGradeDetail(
                member.getKeycloakUserId(),
                new CurrentGrade(grade.getCode(), grade.getName(), grade.getDiscountRate()),
                history);
    }

    private static GradePolicy toPolicy(MemberGrade grade) {
        return new GradePolicy(grade.getCode(), grade.getName(), grade.getDiscountRate(),
                grade.getMinSpendAmount(), grade.getSortOrder(), grade.isDefault());
    }

    /**
     * 기준액이 같은 등급이 둘이면 재산정이 둘 중 무엇을 줄지 정해지지 않는다
     * ({@code findByMinSpendAmountIsNotNullOrderByMinSpendAmountDesc} 의 동률 순서는 보장되지 않는다).
     */
    private void rejectDuplicateThreshold(String code, BigDecimal minSpendAmount) {
        if (minSpendAmount == null) {
            return;
        }
        boolean duplicated = memberGradeRepository.findAll().stream()
                .anyMatch(g -> !g.getCode().equals(code)
                        && g.getMinSpendAmount() != null
                        && g.getMinSpendAmount().compareTo(minSpendAmount) == 0);
        if (duplicated) {
            throw new AdminMemberGradeException(HttpStatus.CONFLICT, "THRESHOLD_CONFLICT",
                    "기준액이 같은 다른 등급이 있습니다.");
        }
    }

    private static boolean samePolicy(MemberGrade grade, String name, BigDecimal discountRate,
            BigDecimal minSpendAmount, Integer sortOrder) {
        return grade.getName().equals(name)
                && grade.getDiscountRate().compareTo(discountRate) == 0
                && sameAmount(grade.getMinSpendAmount(), minSpendAmount)
                && grade.getSortOrder().equals(sortOrder);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String validateName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 50) {
            throw badRequest("INVALID_NAME", "등급 이름은 1~50자여야 합니다.");
        }
        return name;
    }

    private static BigDecimal validateDiscountRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(MAX_DISCOUNT_RATE) > 0
                || rate.stripTrailingZeros().scale() > 2) {
            throw badRequest("INVALID_DISCOUNT_RATE", "할인율은 0~100 사이, 소수 둘째 자리까지여야 합니다.");
        }
        return rate;
    }

    private static BigDecimal validateMinSpendAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        if (amount.signum() < 0 || amount.compareTo(MAX_SPEND_AMOUNT) > 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw badRequest("INVALID_MIN_SPEND_AMOUNT", "기준액은 0원 이상이어야 합니다.");
        }
        return amount;
    }

    private static Integer validateSortOrder(Integer sortOrder) {
        if (sortOrder == null || sortOrder < 0) {
            throw badRequest("INVALID_SORT_ORDER", "정렬 순서는 0 이상의 정수여야 합니다.");
        }
        return sortOrder;
    }

    private static AdminMemberGradeException badRequest(String error, String message) {
        return new AdminMemberGradeException(HttpStatus.BAD_REQUEST, error, message);
    }

    private static AdminMemberGradeException gradeNotFound(String code) {
        return new AdminMemberGradeException(HttpStatus.NOT_FOUND, "GRADE_NOT_FOUND", "등급이 없습니다: " + code);
    }

    private static AdminMemberGradeException memberNotFound(String keycloakUserId) {
        return new AdminMemberGradeException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND",
                "로컬 회원이 없습니다: " + keycloakUserId);
    }
}
