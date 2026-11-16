package com.example.recommend.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;

import java.security.Key;
import java.util.Date;

@Component
public class JwtUtil {

    private final Key key;

    public JwtUtil(@Value("${JWT_SECRET}") String secret) {
        if (secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // 토큰 생성
    public String generateToken(String email, Long userId, long tokenVersion) {
        long now = System.currentTimeMillis();
        long expiry = 1000L * 60 * 60; // 1시간

        return Jwts.builder()
                .claim("uid", userId)
                .claim("ver", tokenVersion)
                .setSubject(email)          // 토큰 주인 (우리는 이메일로)
                .setIssuedAt(new Date(now)) // 발급 시간
                .setExpiration(new Date(now + expiry)) // 만료 시간
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // 토큰에서 이메일(Subject) 꺼내기 + 유효성 검사
    public io.jsonwebtoken.Claims validateAndGetClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
