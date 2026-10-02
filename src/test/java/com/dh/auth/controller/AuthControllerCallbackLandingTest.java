package com.dh.auth.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dh.auth.security.KeycloakClient;
import com.dh.auth.service.EmailVerificationService;
import com.dh.auth.service.MemberPurgeService;
import com.dh.auth.service.MemberService;
import com.dh.auth.service.PhoneVerificationService;

import jakarta.servlet.http.Cookie;

/**
 * 소셜 로그인 콜백이 로그인 뒤 어디로 보내는지 고정한다 (auth.api#42).
 *
 * <p>약관 동의·휴대폰 인증을 안 마친 회원이 곧바로 마이페이지로 가면 그 절차를 받을 기회가 없다 —
 * 콜백이 유일하게 "방금 소셜 로그인했다"를 아는 지점이다.
 */
class AuthControllerCallbackLandingTest {

    private static final String SUB = "sub-1";

    private KeycloakClient keycloakClient;
    private MemberService memberService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        keycloakClient = mock(KeycloakClient.class);
        memberService = mock(MemberService.class);
        when(keycloakClient.authorizationCodeGrant(eq("code-1"), anyString()))
                .thenReturn(new KeycloakClient.TokenResponse("access", 300, "refresh", 1800));
        when(keycloakClient.userInfo("access"))
                .thenReturn(new KeycloakClient.UserInfo(SUB, "social@example.com", "홍길동", true));
        mvc = MockMvcBuilders
                .standaloneSetup(new AuthController(
                        keycloakClient,
                        mock(EmailVerificationService.class),
                        mock(PhoneVerificationService.class),
                        memberService,
                        mock(MemberPurgeService.class),
                        ".posselect.com"))
                .build();
    }

    private ResultActions callback() throws Exception {
        return mvc.perform(get("/api/auth/callback")
                .param("code", "code-1")
                .param("state", "state-1")
                .cookie(new Cookie("oauth_state", "state-1")));
    }

    @Test
    @DisplayName("최초 소셜 로그인: 회원을 만들고 온보딩으로 보낸다")
    void 최초_로그인은_온보딩으로() throws Exception {
        when(memberService.existsByKeycloakUserId(SUB)).thenReturn(false);
        when(memberService.isOnboardingRequired(SUB)).thenReturn(Optional.of(true));

        callback().andExpect(status().isFound()).andExpect(header().string("Location", "/onboarding"));
        verify(memberService).createMemberForSocialLogin(SUB);
    }

    @Test
    @DisplayName("온보딩을 안 마치고 다시 로그인한 회원도 온보딩으로 보낸다")
    void 미완료_회원은_다시_온보딩으로() throws Exception {
        when(memberService.existsByKeycloakUserId(SUB)).thenReturn(true);
        when(memberService.isOnboardingRequired(SUB)).thenReturn(Optional.of(true));

        callback().andExpect(status().isFound()).andExpect(header().string("Location", "/onboarding"));
        verify(memberService, never()).createMemberForSocialLogin(SUB);
    }

    @Test
    @DisplayName("온보딩을 마친 회원은 마이페이지로 보낸다")
    void 완료_회원은_마이페이지로() throws Exception {
        when(memberService.existsByKeycloakUserId(SUB)).thenReturn(true);
        when(memberService.isOnboardingRequired(SUB)).thenReturn(Optional.of(false));

        callback().andExpect(status().isFound()).andExpect(header().string("Location", "/mypage"));
    }
}
