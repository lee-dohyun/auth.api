package com.dh.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dh.auth.entity.Notification;
import com.dh.auth.service.NotificationService;
import com.dh.auth.service.NotificationService.CreateResult;
import com.dh.auth.service.NotificationService.Inbox;

/** 알림함 HTTP 계약 고정 (gateway#180). */
class NotificationControllerTest {

    private static final String CREATE_BODY = "{\"userId\":\"sub-1\",\"type\":\"ORDER_PAID\",\"title\":\"결제 완료\","
            + "\"linkUrl\":\"https://customer.posselect.com/mypage\",\"dedupKey\":\"order-paid:1\"}";

    private NotificationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new NotificationController(service), new InternalNotificationController(service)).build();
    }

    @Test
    @DisplayName("X-User-Id 가 없으면 조회·읽음 전부 401")
    void 헤더가_없으면_401() throws Exception {
        mvc.perform(get("/api/auth/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/notifications/" + UUID.randomUUID() + "/read")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/notifications/read-all")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("목록은 공개 id·읽음 여부를 내보내고 순번 PK 는 내보내지 않는다")
    void 목록_응답() throws Exception {
        Notification n = new Notification(1L, "ORDER_PAID", "결제 완료", "주문 #1", "https://x.posselect.com/a", "k");
        when(service.inbox("sub-1", 20)).thenReturn(Optional.of(new Inbox(1, List.of(n))));

        mvc.perform(get("/api/auth/notifications").header("X-User-Id", "sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].id").value(n.getPublicId().toString()))
                .andExpect(jsonPath("$.items[0].read").value(false))
                .andExpect(jsonPath("$.items[0].title").value("결제 완료"))
                .andExpect(jsonPath("$.items[0].memberId").doesNotExist())
                .andExpect(jsonPath("$.items[0].dedupKey").doesNotExist());
    }

    @Test
    @DisplayName("읽음: 내 알림이 아니면 404, 맞으면 204")
    void 읽음_처리() throws Exception {
        UUID mine = UUID.randomUUID();
        UUID notMine = UUID.randomUUID();
        when(service.markRead("sub-1", mine)).thenReturn(true);
        when(service.markRead("sub-1", notMine)).thenReturn(false);

        mvc.perform(post("/api/auth/notifications/" + mine + "/read").header("X-User-Id", "sub-1"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/notifications/" + notMine + "/read").header("X-User-Id", "sub-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("내부 등록: 처음 201, 중복 200, 회원 없음 404")
    void 내부_등록_결과_매핑() throws Exception {
        when(service.create(any(), any(), any(), any(), any(), any()))
                .thenReturn(CreateResult.CREATED, CreateResult.DUPLICATE, CreateResult.MEMBER_NOT_FOUND);

        mvc.perform(post("/internal/notifications").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());
        mvc.perform(post("/internal/notifications").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/notifications").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("내부 등록: 동시 요청이 dedup UNIQUE 에 걸리면 500 이 아니라 200(중복)")
    void 내부_등록_동시_중복() throws Exception {
        when(service.create(any(), any(), any(), any(), any(), any())).thenThrow(new DataIntegrityViolationException(
                "x", new RuntimeException("duplicate key value violates unique constraint \"uq_notifications_member_dedup\"")));

        mvc.perform(post("/internal/notifications").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("내부 등록: https 가 아닌 링크·소문자 type 은 400")
    void 내부_등록_검증() throws Exception {
        String badLink = CREATE_BODY.replace("https://customer.posselect.com/mypage", "javascript:alert(1)");
        String badType = CREATE_BODY.replace("ORDER_PAID", "order paid");

        mvc.perform(post("/internal/notifications").contentType(MediaType.APPLICATION_JSON).content(badLink))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/notifications").contentType(MediaType.APPLICATION_JSON).content(badType))
                .andExpect(status().isBadRequest());
    }
}
