package com.dh.auth.controller;

import java.security.Key;
import java.util.Date;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

public class JWTUtil {
	private static final String SECRET_KEY = "your-256-bit-secret-your-256-bit-secret"; // 32+ chars for HS256

	public static String generateSampleToken(String subject) {
		long nowMillis = System.currentTimeMillis();
		long expMillis = nowMillis + 3600_000; // 1 hour
		Date now = new Date(nowMillis);
		Date exp = new Date(expMillis);

		Key key = Keys.hmacShaKeyFor(SECRET_KEY.getBytes());

		return Jwts.builder().setSubject(subject).claim("GRADE", "NEW").setIssuedAt(now).setExpiration(exp)
				.signWith(key).compact();
	}
}
