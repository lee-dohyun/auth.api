package com.dh.auth.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JwtController {

	@GetMapping("/api/jwt/sample")
	public String getSampleJwt(@RequestParam(name = "username", defaultValue = "sampleUser") String username) {
		return JWTUtil.generateSampleToken(username);
	}

	@PostMapping("/api/jwt/login")
	public String login(@RequestParam(name = "email") String email, @RequestParam(name = "password") String password) {
		// Hardcoded credentials for sample
		String validEmail = "test@example.com";
		String validPassword = "password123";

		if (validEmail.equals(email) && validPassword.equals(password)) {
			// Issue JWT token
			return JWTUtil.generateSampleToken(email);
		} else {
			return "Invalid email or password";
		}
	}
}
