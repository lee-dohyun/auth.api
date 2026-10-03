package com.dh.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dh.auth.config.AdminAuthInterceptor;
import com.dh.auth.config.AdminPrincipal;
import com.dh.auth.dto.AdminMemberGradeDtos.CurrentGrade;
import com.dh.auth.dto.AdminMemberGradeDtos.GradeAdjustRequest;
import com.dh.auth.dto.AdminMemberGradeDtos.GradeAdjustResponse;
import com.dh.auth.dto.AdminMemberGradeDtos.GradeHistoryItem;
import com.dh.auth.dto.AdminMemberGradeDtos.GradeLock;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicy;
import com.dh.auth.dto.AdminMemberGradeDtos.GradePolicyCreateResult;
import com.dh.auth.dto.AdminMemberGradeDtos.MemberGradeDetail;
import com.dh.auth.service.AdminMemberGradeException;
import com.dh.auth.service.AdminMemberGradeService;

/**
 * 관리자 등급 API 의 HTTP 계약(경로·상태 코드·JSON 필드 이름) 고정 (gateway#80).
 *
 * <p>admin.front 가 이 필드 이름에 기대므로 여기서 고정한다. 규칙 자체(멱등성·검증·FK)는
 * {@code AdminMemberGradeServiceIntegrationTest} 가 실제 DB 로 검증한다. 인가는 인터셉터 몫이라
 * 여기서는 보지 않는다({@code AdminAuthInterceptorTest}).
 */
class AdminMemberGradeControllerTest {

    private final AdminMemberGradeService service = mock(AdminMemberGradeService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminMemberGradeController(service)).build();

    private static final GradePolicy SILVER =
            new GradePolicy("SILVER", "실버", new BigDecimal("2.00"), new BigDecimal("300000.00"), 2, false);

    @Test
    @DisplayName("목록은 items 배열, 순번 PK(id) 없이 isDefault 필드 이름으로 나간다")
    void 목록_JSON() throws Exception {
        when(service.listPolicies()).thenReturn(List.of(SILVER));

        mvc.perform(get("/api/admin/member-grades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("SILVER"))
                .andExpect(jsonPath("$.items[0].discountRate").value(2.00))
                .andExpect(jsonPath("$.items[0].minSpendAmount").value(300000.00))
                .andExpect(jsonPath("$.items[0].isDefault").value(false))
                .andExpect(jsonPath("$.items[0].id").doesNotExist());
    }

    @Test
    @DisplayName("생성은 새로 만들면 201, 같은 내용이 이미 있으면 200")
    void 생성_상태코드() throws Exception {
        String body = """
                {"code":"SILVER","name":"실버","discountRate":2.0,"minSpendAmount":300000,"sortOrder":2}""";

        when(service.createPolicy(any())).thenReturn(new GradePolicyCreateResult(SILVER, true));
        mvc.perform(post("/api/admin/member-grades").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SILVER"));

        when(service.createPolicy(any())).thenReturn(new GradePolicyCreateResult(SILVER, false));
        mvc.perform(post("/api/admin/member-grades").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("거부는 서비스가 정한 상태 코드와 {error, message} 본문으로 나간다")
    void 거부_본문() throws Exception {
        doThrow(new AdminMemberGradeException(HttpStatus.CONFLICT, "GRADE_IN_USE", "쓰이는 등급"))
                .when(service).deletePolicy("GOLD");

        mvc.perform(delete("/api/admin/member-grades/GOLD"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("GRADE_IN_USE"))
                .andExpect(jsonPath("$.message").value("쓰이는 등급"));
    }

    @Test
    @DisplayName("삭제 성공은 204")
    void 삭제_성공() throws Exception {
        mvc.perform(delete("/api/admin/member-grades/TEMP")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("수동 조정은 처리자 이메일을 토큰(요청 속성)에서 읽어 서비스에 넘기고 changed·member 를 돌려준다")
    void 수동_조정() throws Exception {
        MemberGradeDetail detail = new MemberGradeDetail("sub-1", new CurrentGrade("VIP", "VIP", new BigDecimal("10.00")),
                new GradeLock(true, null),
                List.of(new GradeHistoryItem("VIP", "VIP", "수동 조정: CS 보상", LocalDateTime.of(2026, 10, 2, 12, 0))));
        when(service.adjustMemberGrade(eq("sub-1"), eq(new GradeAdjustRequest("VIP", "CS 보상")), eq("admin@posselect.com")))
                .thenReturn(new GradeAdjustResponse(true, detail));

        mvc.perform(put("/api/admin/members/sub-1/grade")
                        .requestAttr(AdminAuthInterceptor.PRINCIPAL_ATTRIBUTE,
                                new AdminPrincipal("admin@posselect.com", Set.of("MEMBER_MANAGER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gradeCode":"VIP","reason":"CS 보상"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.member.keycloakUserId").value("sub-1"))
                .andExpect(jsonPath("$.member.grade.code").value("VIP"))
                .andExpect(jsonPath("$.member.lock.locked").value(true))
                .andExpect(jsonPath("$.member.lock.until").doesNotExist())
                .andExpect(jsonPath("$.member.history[0].reason").value("수동 조정: CS 보상"))
                .andExpect(jsonPath("$.member.history[0].gradeCode").value("VIP"));
    }

    @Test
    @DisplayName("수동 조정의 유지 기한은 lockedUntil(YYYY-MM-DD)로 받고, 응답은 lock.until 로 같은 형식을 돌려준다 (#49)")
    void 수동_조정_유지_기한() throws Exception {
        LocalDate until = LocalDate.of(2026, 12, 31);
        MemberGradeDetail detail = new MemberGradeDetail("sub-1", new CurrentGrade("VIP", "VIP", new BigDecimal("10.00")),
                new GradeLock(true, until), List.of());
        when(service.adjustMemberGrade(eq("sub-1"), eq(new GradeAdjustRequest("VIP", "연말까지", until)), eq("unknown")))
                .thenReturn(new GradeAdjustResponse(true, detail));

        mvc.perform(put("/api/admin/members/sub-1/grade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gradeCode":"VIP","reason":"연말까지","lockedUntil":"2026-12-31"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.lock.locked").value(true))
                .andExpect(jsonPath("$.member.lock.until").value("2026-12-31"));
    }

    @Test
    @DisplayName("고정 해제는 DELETE .../grade/lock 이고 changed·member 를 돌려준다 (#49)")
    void 고정_해제() throws Exception {
        MemberGradeDetail detail = new MemberGradeDetail("sub-1", new CurrentGrade("VIP", "VIP", new BigDecimal("10.00")),
                new GradeLock(false, null), List.of());
        when(service.releaseGradeLock("sub-1", "admin@posselect.com")).thenReturn(new GradeAdjustResponse(true, detail));

        mvc.perform(delete("/api/admin/members/sub-1/grade/lock")
                        .requestAttr(AdminAuthInterceptor.PRINCIPAL_ATTRIBUTE,
                                new AdminPrincipal("admin@posselect.com", Set.of("MEMBER_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.member.lock.locked").value(false));
    }
}
