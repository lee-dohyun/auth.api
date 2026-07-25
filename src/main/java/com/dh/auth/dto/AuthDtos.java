package com.dh.auth.dto;

public class AuthDtos {

    public record SignupRequest(String email, String password) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record MeResponse(String email, String role) {
    }
}
