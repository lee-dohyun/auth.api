package com.dh.auth.controller;

import java.security.Key;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

public class JWTUtil {
    private JWTUtil() {
        // Prevent instantiation
    }

    private static final String SECRET_KEY = "your-256-bit-secret-your-256-bit-secret"; // 32+ chars for HS256

    public static String generateSampleToken(String subject) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime exp = now.plusHours(1);

        Key key = Keys.hmacShaKeyFor(SECRET_KEY.getBytes());

        return Jwts.builder()
                .setSubject(subject)
                .claim("GRADE", "NEW")
                .setIssuedAt(localDateTimeToDate(now))
                .setExpiration(localDateTimeToDate(exp))
                .signWith(key)
                .compact();
    }

    private static Date localDateTimeToDate(LocalDateTime ldt) {
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }
}
