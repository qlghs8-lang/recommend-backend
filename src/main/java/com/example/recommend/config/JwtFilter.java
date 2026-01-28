package com.example.recommend.config;

import com.example.recommend.domain.User;
import com.example.recommend.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // 토큰 없으면 다음 필터
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        try {
            String email = jwtUtil.validateAndGetEmail(token);

            // ✅ role 조회 (없으면 USER)
            User user = userRepository.findByEmail(email).orElse(null);

            String role = (user == null || user.getRole() == null || user.getRole().isBlank())
                    ? "USER"
                    : user.getRole().trim().toUpperCase();

            // ✅ 핵심: DB에 "ROLE_ADMIN"처럼 저장된 경우 중복 접두사 제거
            if (role.startsWith("ROLE_")) {
                role = role.substring("ROLE_".length());
            }

            // hasRole("ADMIN") => 실제 권한은 "ROLE_ADMIN"
            List<SimpleGrantedAuthority> authorities =
                    List.of(new SimpleGrantedAuthority("ROLE_" + role));

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(email, null, authorities);

            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);

        } catch (Exception e) {
            // 토큰 문제면 인증 제거 -> 결과적으로 401/403
            SecurityContextHolder.clearContext();

            // ✅ 선택(권장): 개발 중 디버깅 편하게 명시적으로 401 반환
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
    }
}

