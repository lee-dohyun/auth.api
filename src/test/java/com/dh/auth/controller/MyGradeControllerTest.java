package com.dh.auth.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dh.auth.service.MyGradeService;
import com.dh.auth.service.MyGradeService.MyGrade;
import com.dh.auth.service.MyGradeService.NextGrade;

/** GET /api/auth/me/grade 의 HTTP 계약 고정 (gateway#81). */
class MyGradeControllerTest {

    private MyGradeService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(MyGradeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MyGradeController(service)).build();
    }

    @Test
    @DisplayName("X-User-Id 가 없으면(게이트웨이를 안 거친 호출) 401")
    void 헤더가_없으면_401() throws Exception {
        mvc.perform(get("/api/auth/me/grade")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로컬 회원 행이 없는 sub 는 404")
    void 회원이_없으면_404() throws Exception {
        when(service.find("sub-1")).thenReturn(Optional.empty());

        mvc.perform(get("/api/auth/me/grade").header("X-User-Id", "sub-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("등급·혜택·다음 등급·남은 금액을 JSON 으로 돌려준다")
    void 정상_응답() throws Exception {
        when(service.find("sub-1")).thenReturn(Optional.of(new MyGrade(
                "SILVER", "실버", new BigDecimal("2.00"), 6,
                new NextGrade("GOLD", "골드", new BigDecimal("5.00"), new BigDecimal("1000000.00")),
                new BigDecimal("550000.00"))));

        mvc.perform(get("/api/auth/me/grade").header("X-User-Id", "sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SILVER"))
                .andExpect(jsonPath("$.name").value("실버"))
                .andExpect(jsonPath("$.discountRate").value(2.00))
                .andExpect(jsonPath("$.windowMonths").value(6))
                .andExpect(jsonPath("$.nextGrade.code").value("GOLD"))
                .andExpect(jsonPath("$.nextGrade.minSpendAmount").value(1000000.00))
                .andExpect(jsonPath("$.amountToNextGrade").value(550000.00));
    }

    @Test
    @DisplayName("최고 등급이면 nextGrade 와 amountToNextGrade 가 null 로 내려간다")
    void 최고_등급() throws Exception {
        when(service.find("sub-1")).thenReturn(Optional.of(new MyGrade(
                "VIP", "VIP", new BigDecimal("10.00"), 6, null, null)));

        mvc.perform(get("/api/auth/me/grade").header("X-User-Id", "sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextGrade").value((Object) null))
                .andExpect(jsonPath("$.amountToNextGrade").value((Object) null));
    }
}
