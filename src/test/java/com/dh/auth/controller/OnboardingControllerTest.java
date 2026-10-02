package com.dh.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dh.auth.config.Messages;
import com.dh.auth.service.MemberService;
import com.dh.auth.service.MemberService.OnboardingResult;
import com.dh.auth.service.PhoneVerificationService;

/** /api/auth/onboarding 의 HTTP 계약 고정 (auth.api#42). */
class OnboardingControllerTest {

    private static final String PHONE = "+821012345678";
    private static final String BODY =
            "{\"phoneNumber\":\"" + PHONE + "\",\"agreeTerms\":true,\"agreePrivacy\":true,\"marketingOptIn\":true}";

    private MemberService memberService;
    private PhoneVerificationService phoneVerificationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        memberService = mock(MemberService.class);
        phoneVerificationService = mock(PhoneVerificationService.class);
        Messages messages = mock(Messages.class);
        when(messages.get("onboarding.phoneInUse")).thenReturn("이미 사용 중");
        // 기본은 "온보딩이 남은 회원". 완료 요청은 이 판정을 지나야 인증 확인으로 간다.
        when(memberService.isOnboardingRequired("sub-1")).thenReturn(Optional.of(true));
        mvc = MockMvcBuilders
                .standaloneSetup(new OnboardingController(memberService, phoneVerificationService, messages))
                .build();
    }

    @Test
    @DisplayName("조회: X-User-Id 가 없으면 401")
    void 조회_헤더가_없으면_401() throws Exception {
        mvc.perform(get("/api/auth/onboarding")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("조회: 로컬 회원 행이 없는 sub 는 404")
    void 조회_회원이_없으면_404() throws Exception {
        when(memberService.isOnboardingRequired("sub-1")).thenReturn(Optional.empty());
        mvc.perform(get("/api/auth/onboarding").header("X-User-Id", "sub-1")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("조회: 전화번호가 없는 회원은 required=true")
    void 조회_온보딩_필요() throws Exception {
        when(memberService.isOnboardingRequired("sub-1")).thenReturn(Optional.of(true));
        mvc.perform(get("/api/auth/onboarding").header("X-User-Id", "sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true));
    }

    @Test
    @DisplayName("완료: X-User-Id 가 없으면 401")
    void 완료_헤더가_없으면_401() throws Exception {
        mvc.perform(post("/api/auth/onboarding").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("완료: 필수 약관 중 하나라도 동의하지 않으면 400 AGREEMENTS_REQUIRED, 회원은 건드리지 않는다")
    void 완료_필수약관_미동의() throws Exception {
        String body = "{\"phoneNumber\":\"" + PHONE + "\",\"agreeTerms\":true,\"agreePrivacy\":false}";
        mvc.perform(post("/api/auth/onboarding").header("X-User-Id", "sub-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AGREEMENTS_REQUIRED"));
        verify(memberService, never()).completeSocialOnboarding(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("완료: 최근 인증된 번호가 아니면 400 PHONE_NOT_VERIFIED, 회원은 건드리지 않는다")
    void 완료_전화번호_미인증() throws Exception {
        when(phoneVerificationService.isRecentlyVerified(PHONE)).thenReturn(false);
        mvc.perform(post("/api/auth/onboarding").header("X-User-Id", "sub-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PHONE_NOT_VERIFIED"));
        verify(memberService, never()).completeSocialOnboarding(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("완료: 정상 처리는 200")
    void 완료_정상() throws Exception {
        when(phoneVerificationService.isRecentlyVerified(PHONE)).thenReturn(true);
        when(memberService.completeSocialOnboarding("sub-1", PHONE, true)).thenReturn(OnboardingResult.COMPLETED);
        mvc.perform(post("/api/auth/onboarding").header("X-User-Id", "sub-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        verify(memberService, times(1)).completeSocialOnboarding("sub-1", PHONE, true);
    }

    @Test
    @DisplayName("완료: 이미 마친 회원의 재전송은 인증 이력이 조회되지 않아도 200, 회원은 건드리지 않는다 (#52)")
    void 완료_재전송() throws Exception {
        // 실제 재전송에서는 첫 요청이 인증 이력을 회원에 연결해 "최근 인증"이 false 가 된다.
        when(memberService.isOnboardingRequired("sub-1")).thenReturn(Optional.of(false));
        when(phoneVerificationService.isRecentlyVerified(PHONE)).thenReturn(false);
        mvc.perform(post("/api/auth/onboarding").header("X-User-Id", "sub-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        verify(memberService, never()).completeSocialOnboarding(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("완료: 로컬 회원 행이 없는 sub 는 404")
    void 완료_회원이_없으면_404() throws Exception {
        when(memberService.isOnboardingRequired("sub-1")).thenReturn(Optional.empty());
        mvc.perform(post("/api/auth/onboarding").header("X-User-Id", "sub-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("완료: 다른 회원이 쓰는 번호면 409 와 안내 문구")
    void 완료_번호_중복() throws Exception {
        when(phoneVerificationService.isRecentlyVerified(PHONE)).thenReturn(true);
        when(memberService.completeSocialOnboarding("sub-1", PHONE, true)).thenReturn(OnboardingResult.PHONE_IN_USE);
        mvc.perform(post("/api/auth/onboarding").header("X-User-Id", "sub-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("이미 사용 중"));
    }
}
