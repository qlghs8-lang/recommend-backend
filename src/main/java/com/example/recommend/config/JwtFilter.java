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

    // ✅ Swagger/OpenAPI + 공개 엔드포인트는 JWT 필터에서 아예 제외
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        // swagger
        if (path.equals("/v3/api-docs") || path.startsWith("/v3/api-docs/")) return true;
        if (path.equals("/swagger-ui.html") || path.startsWith("/swagger-ui/")) return true;

        // public endpoints (필요한 것만 추가)
        if (path.startsWith("/uploads/")) return true;
        if (path.equals("/login")) return true;
        if (path.equals("/users")) return true;
        if (path.equals("/users/check-email")) return true;
        if (path.equals("/users/check-nickname")) return true;
        if (path.startsWith("/public/phone/")) return true;

        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // 토큰 없으면 다음 필터(= 인증 없음 상태로 진행)
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

            filterChain.doFilter(request, response);

        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
