package com.dh.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.dh.auth.service.MyGradeService;

/**
 * 로그인한 고객의 등급 조회(gateway#81). 마이페이지와 장바구니의 할인 안내가 쓴다.
 *
 * <p>회원 식별은 {@code X-User-Id}(Keycloak sub)로만 한다 — 이메일은 바뀔 수 있고, 등급은 금전적
 * 혜택이라 주문 쪽과 같은 키를 써야 한다. 게이트웨이가 클라이언트가 보낸 {@code X-User-*} 를 지우고
 * JWT 검증 후에만 다시 채우므로 헤더가 없으면 비로그인이다.
 */
@RestController
public class MemberGradeController {

    private final MyGradeService myGradeService;

    public MemberGradeController(MyGradeService myGradeService) {
        this.myGradeService = myGradeService;
    }

    @GetMapping("/api/auth/me/grade")
    public ResponseEntity<MyGradeService.MyGrade> myGrade(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return myGradeService.describe(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
