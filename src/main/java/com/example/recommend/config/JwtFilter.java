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

    // ✅ Swagger / Public endpoint는 JWT 필터 자체를 타지 않게
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();

        return path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/uploads/")
                || path.equals("/login")
                || path.equals("/users")
                || path.startsWith("/users/check-")
                || path.startsWith("/public/phone/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // 토큰 없으면 다음 필터(= 익명 사용자로 진행)
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

            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));

            var authentication =
                    new UsernamePasswordAuthenticationToken(email, null, authorities);

            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);

        } catch (Exception e) {
            // ✅ 여기서 401을 “직접” 찍어버리면 swagger 같은 permitAll도 막아버림.
            // 토큰이 잘못됐으면 그냥 인증 없이 진행시키고, 최종 인가 단계에서 걸리게 하자.
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
        }
    }
}
