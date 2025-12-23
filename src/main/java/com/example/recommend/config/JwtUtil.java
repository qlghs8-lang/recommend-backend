package com.example.recommend.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Component
public class JwtUtil {

    // 최소 32글자 이상 아무 문자열 (진짜 서비스에서는 외부 설정으로 빼야 함)
    private static final String SECRET_KEY = "my_super_secret_jwt_key_1234567890";

    private final Key key = Keys.hmacShaKeyFor(SECRET_KEY.getBytes());

    // 토큰 생성
    public String generateToken(String email) {
        long now = System.currentTimeMillis();
        long expiry = 1000L * 60 * 60; // 1시간

        return Jwts.builder()
                .setSubject(email)          // 토큰 주인 (우리는 이메일로)
                .setIssuedAt(new Date(now)) // 발급 시간
                .setExpiration(new Date(now + expiry)) // 만료 시간
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // 토큰에서 이메일(Subject) 꺼내기 + 유효성 검사
    public String validateAndGetEmail(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }
}
