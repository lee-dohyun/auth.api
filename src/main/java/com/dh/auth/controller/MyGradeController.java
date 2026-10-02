package com.dh.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.dh.auth.service.MyGradeService;
import com.dh.auth.service.MyGradeService.MyGrade;

/**
 * 마이페이지 등급 카드용 조회 (gateway#81).
 *
 * <p>회원은 게이트웨이가 주입한 {@code X-User-Id}(Keycloak sub)로만 식별한다 — 경로·쿼리로 회원을
 * 받지 않으므로 남의 등급을 조회할 방법이 없다. 게이트웨이를 거치지 않은 호출은 헤더가 없어 401.
 * 로그인 후에만 부르는 경로라 gateway {@code PUBLIC_EXACT_PATHS} 등록 대상이 아니다.
 */
@RestController
public class MyGradeController {

    private final MyGradeService myGradeService;

    public MyGradeController(MyGradeService myGradeService) {
        this.myGradeService = myGradeService;
    }

    @GetMapping("/api/auth/me/grade")
    public ResponseEntity<MyGrade> myGrade(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return myGradeService.find(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
