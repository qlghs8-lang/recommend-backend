package com.example.recommend.config;

import com.example.recommend.domain.User;
import com.example.recommend.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
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

    // ✅ 필터에서 완전히 제외할 경로들 (permitAll과 "같은 목록"으로 맞추는 게 안전)
    private static final String[] WHITELIST = {
            "/v3/api-docs", "/v3/api-docs/",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/uploads/**",
            "/login",
            "/users",
            "/users/check-email",
            "/users/check-nickname",
            "/public/phone/**"
    };

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();

        // 아주 간단 매칭(/** 지원용)
        for (String p : WHITELIST) {
            if (p.endsWith("/**")) {
                String prefix = p.substring(0, p.length() - 3);
                if (path.startsWith(prefix)) return true;
            } else {
                if (path.equals(p)) return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 토큰 없으면 인증 세팅 없이 다음 필터로 (permitAll은 여기서 통과해야 함)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        try {
            String email = jwtUtil.validateAndGetEmail(token);

            User user = userRepository.findByEmail(email).orElse(null);

            String role = (user == null || user.getRole() == null || user.getRole().isBlank())
                    ? "USER"
                    : user.getRole().trim().toUpperCase();

            if (role.startsWith("ROLE_")) {
                role = role.substring("ROLE_".length());
            }

            List<SimpleGrantedAuthority> authorities =
                    List.of(new SimpleGrantedAuthority("ROLE_" + role));

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(email, null, authorities);

            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (Exception e) {
            // ✅ 여기서 401로 끊지 말고 그냥 인증 없이 진행
            // (SecurityConfig의 anyRequest().authenticated()가 필요한 곳에서 401/403 처리함)
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
