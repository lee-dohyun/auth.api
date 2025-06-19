package com.dh.auth.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JwtController {

    @GetMapping("/api/jwt/sample")
    public String getSampleJwt(@RequestParam(name = "username", defaultValue = "sampleUser") String username) {
        return JWTUtil.generateSampleToken(username);
    }
}
