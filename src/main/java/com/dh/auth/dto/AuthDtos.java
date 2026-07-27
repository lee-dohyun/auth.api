package com.dh.auth.dto;

public class AuthDtos {

    public record SignupRequest(String email, String password, String name) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record MeResponse(String email, String name) {
    }

    public record UpdateMeRequest(String email, String name, String password) {
    }
}
