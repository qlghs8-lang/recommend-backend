package com.example.recommend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // ✅ 기본 로그인/BasicAuth 완전 비활성화 (generated password 제거 목적)
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable());

        http
            .authorizeHttpRequests(auth -> auth

                // ✅ Swagger / OpenAPI (채용담당자 확인용)
                .requestMatchers(
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs",
                    "/v3/api-docs/**"
                ).permitAll()

                // 정적 리소스
                .requestMatchers("/uploads/**").permitAll()

                // 인증 없이 접근 가능한 API
                .requestMatchers(
                    "/login",
                    "/users",
                    "/users/check-email",
                    "/users/check-nickname",
                    "/public/phone/**"
                ).permitAll()

                // ✅ (선택) 채용담당자 데모용 추천 API만 오픈
