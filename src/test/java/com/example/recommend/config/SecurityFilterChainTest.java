package com.example.recommend.config;

import com.example.recommend.domain.User;
import com.example.recommend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.bind.annotation.*;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SecurityFilterChainTest {
    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtFilter.class, com.example.recommend.controller.UserController.class, Probe.class, SmsConfig.class,
        com.example.recommend.controller.PublicPhoneController.class,
        com.example.recommend.service.PhoneVerificationService.class})
    static class TestConfig {
        @Bean JwtUtil jwtUtil() { return new JwtUtil("isolated-test-secret-at-least-32-bytes"); }
        @Bean com.example.recommend.service.UserService userService() { return mock(com.example.recommend.service.UserService.class); }
        @Bean com.example.recommend.service.ProfileImageStorage profileImages() { return mock(com.example.recommend.service.ProfileImageStorage.class); }
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean com.example.recommend.service.PhoneVerificationStore phoneStore(com.example.recommend.service.SmsSender sender) {
            return new com.example.recommend.service.InMemoryPhoneVerificationStore(sender);
        }
    }

    @RestController
    static class Probe {
        @GetMapping("/api/admin/probe") String ok() { return "ok"; }
    }

    @Test void distinguishesUnauthenticatedForbiddenAndAuthorizedRequests() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestConfig.class);
            context.refresh();
            var users = context.getBean(UserRepository.class);
            when(users.findByEmail("user@example.com")).thenReturn(Optional.of(User.builder().id(1L).role("USER").build()));
            when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(User.builder().id(1L).role("ADMIN").build()));
            var jwt = context.getBean(JwtUtil.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            mvc.perform(post("/logout")).andExpect(status().isUnauthorized());
            mvc.perform(post("/logout").header("Authorization", "Bearer " + jwt.generateToken("user@example.com", 1L, 0L)))
                    .andExpect(status().isOk());
            verify(context.getBean(com.example.recommend.service.UserService.class)).logout("user@example.com");
            mvc.perform(get("/user/me")).andExpect(status().isUnauthorized());
            mvc.perform(post("/user/phone/request").contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/user/phone/verify").contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/public/phone/request").contentType("application/json")
                    .content("{\"phone\":\"01012345678\",\"requesterToken\":\"" + "a".repeat(64) + "\"}"))
                    .andExpect(status().isServiceUnavailable());
            mvc.perform(get("/api/admin/probe").header("Authorization", "Bearer " + jwt.generateToken("user@example.com", 1L, 0L)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/probe").header("Authorization", "Bearer " + jwt.generateToken("admin@example.com", 1L, 0L)))
                    .andExpect(status().isOk());
            mvc.perform(get("/user/me").header("Authorization", "Bearer " + jwt.generateToken("deleted@example.com", 1L, 0L)))
                    .andExpect(status().isUnauthorized());
        }
    }
}
