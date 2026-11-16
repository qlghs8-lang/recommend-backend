package com.example.recommend.config;

import com.example.recommend.domain.User;
import com.example.recommend.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtSecurityTest {
    private static final String SECRET = "test-only-key-with-at-least-32-bytes";
    private final JwtUtil jwt = new JwtUtil(SECRET);
    private final UserRepository users = mock(UserRepository.class);
    private final JwtFilter filter = new JwtFilter(jwt, users);

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void rejectsMissingOrWeakSecret() {
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil(null));
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil("short"));
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil(" ".repeat(32)));
    }

    @Test void authenticatesExistingUserUsingDatabaseRole() throws Exception {
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(User.builder().id(1L).role("ADMIN").build()));
        FilterChain chain = mock(FilterChain.class);
        var request = request(jwt.generateToken("user@example.com", 1L, 0L));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals("user@example.com", SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        verify(chain).doFilter(request, response);
    }

    @Test void rejectsDeletedUser() throws Exception {
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        assertUnauthorized(jwt.generateToken("deleted@example.com", 1L, 0L));
    }

    @Test void rejectsForgedExpiredMalformedAndSubjectlessTokens() throws Exception {
        assertUnauthorized(new JwtUtil("another-test-key-with-at-least-32-bytes").generateToken("user@example.com", 1L, 0L));
        assertUnauthorized(Jwts.builder().setSubject("user@example.com").setExpiration(new Date(0))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256).compact());
        assertUnauthorized("not-a-jwt");
        assertUnauthorized(jwt.generateToken(null, 1L, 0L));
        verifyNoInteractions(users);
    }

    @Test void downstreamFailureIsNotConvertedToUnauthorized() throws Exception {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(User.builder().id(1L).build()));
        var response = new MockHttpServletResponse();
        assertThrows(ServletException.class, () -> filter.doFilter(request(jwt.generateToken("user@example.com", 1L, 0L)),
                response, (req, res) -> { throw new ServletException("downstream failure"); }));
        assertEquals(200, response.getStatus());
    }

    @Test void databaseFailureIsNotConvertedToUnauthorized() {
        when(users.findByEmail(anyString())).thenThrow(new IllegalStateException("database unavailable"));
        var response = new MockHttpServletResponse();
        assertThrows(IllegalStateException.class, () -> filter.doFilter(request(jwt.generateToken("user@example.com", 1L, 0L)),
                response, mock(FilterChain.class)));
        assertEquals(200, response.getStatus());
    }

    @Test void rejectsRevokedLegacyAndRecreatedAccountTokens() throws Exception {
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(User.builder().id(1L).tokenVersion(2).build()));
        assertUnauthorized(jwt.generateToken("user@example.com", 1L, 1L));
        assertUnauthorized(jwt.generateToken("user@example.com", 9L, 2L));
        assertUnauthorized(Jwts.builder().setSubject("user@example.com")
                .setExpiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256).compact());
        var response = new MockHttpServletResponse();
        filter.doFilter(request(jwt.generateToken("user@example.com", 1L, 2L)), response, mock(FilterChain.class));
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private void assertUnauthorized(String token) throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var response = new MockHttpServletResponse();
        filter.doFilter(request(token), response, chain);
        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(chain);
    }

    private MockHttpServletRequest request(String token) {
        var request = new MockHttpServletRequest("GET", "/user/me");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
