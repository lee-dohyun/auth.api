package com.dh.auth.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.dh.auth.config.Messages;
import com.dh.auth.dto.AuthDtos.ErrorResponse;
import com.dh.auth.dto.AuthDtos.OnboardingRequest;
import com.dh.auth.service.MemberService;
import com.dh.auth.service.MemberService.OnboardingResult;
import com.dh.auth.service.PhoneVerificationService;

import jakarta.validation.Valid;

/**
 * 소셜 로그인 회원의 가입 마무리(온보딩) — auth.api#42.
 *
 * <p>일반 가입({@code POST /api/auth/signup})은 필수 약관 동의와 휴대폰 본인 인증을 거쳐야 회원이
 * 만들어지는데, 소셜 로그인 콜백은 IdP 인증만으로 회원 행을 만든다. 그래서 소셜 회원은 전화번호가
 * 비어 있고 약관 동의 절차도 밟지 않은 상태로 남았다. 이 컨트롤러가 그 빠진 절차를 로그인 직후에 받는다.
 *
 * <p>"온보딩이 필요한가"는 별도 플래그 없이 <b>본인 인증 전화번호가 비어 있는가</b>로 판정한다 — 일반 가입은
 * 전화번호 없이는 성립하지 않으므로, 비어 있다는 것 자체가 온보딩을 안 거쳤다는 뜻이다.
 *
 * <p>회원은 게이트웨이가 주입한 {@code X-User-Id}(Keycloak sub)로만 식별한다. 로그인 후에만 부르는 경로라
 * gateway {@code PUBLIC_EXACT_PATHS} 등록 대상이 아니다(등록하면 헤더가 주입되지 않아 항상 401 이 된다).
 */
@Validated
@RestController
public class OnboardingController {

    private final MemberService memberService;
    private final PhoneVerificationService phoneVerificationService;
    private final Messages messages;

    public OnboardingController(
            MemberService memberService, PhoneVerificationService phoneVerificationService, Messages messages) {
        this.memberService = memberService;
        this.phoneVerificationService = phoneVerificationService;
        this.messages = messages;
    }

    /** 온보딩이 남았는지 조회. 로컬 회원 행이 없는 sub 는 404. */
    @GetMapping("/api/auth/onboarding")
    public ResponseEntity<?> status(@RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return memberService.isOnboardingRequired(userId)
                .map(required -> ResponseEntity.ok(Map.of("required", required)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 온보딩 완료 — 필수 약관 동의를 확인하고, 방금 인증한 전화번호를 회원에 연결한다.
     *
     * <p>이미 온보딩을 마친 회원이 다시 호출하면 아무것도 바꾸지 않고 200 을 돌려준다(멱등).
     * 필수 약관 동의 시각은 저장하지 않는다 — 일반 가입도 저장하지 않으며, 두 경로 모두에 동의 이력을
     * 남기는 것은 스키마가 필요한 별도 작업이다.
     */
    @PostMapping("/api/auth/onboarding")
    public ResponseEntity<?> complete(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody OnboardingRequest request) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!Boolean.TRUE.equals(request.agreeTerms()) || !Boolean.TRUE.equals(request.agreePrivacy())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("AGREEMENTS_REQUIRED"));
        }
        // signup 과 같은 기계 코드를 쓴다 — 프론트가 "인증 만료 → 인증 단계로 되돌리기"를 같은 분기로 처리한다.
        if (!phoneVerificationService.isRecentlyVerified(request.phoneNumber())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("PHONE_NOT_VERIFIED"));
        }

        OnboardingResult result = memberService.completeSocialOnboarding(
                userId, request.phoneNumber(), Boolean.TRUE.equals(request.marketingOptIn()));
        return switch (result) {
            case COMPLETED, ALREADY_COMPLETED -> ResponseEntity.ok().build();
            case MEMBER_NOT_FOUND -> ResponseEntity.notFound().build();
            case PHONE_IN_USE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse(messages.get("onboarding.phoneInUse")));
        };
    }
}
