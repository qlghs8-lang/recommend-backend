package com.example.recommend.controller;

import com.example.recommend.config.JwtUtil;
import com.example.recommend.domain.User;
import com.example.recommend.exception.*;
import com.example.recommend.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserApiTest {
    private final UserService service = mock(UserService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserController(service, mock(JwtUtil.class), mock(com.example.recommend.service.ProfileImageStorage.class)))
            .addFilters(new org.springframework.web.filter.CharacterEncodingFilter("UTF-8", true))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void registrationReturnsOnlyPublicAccountFields() throws Exception {
        when(service.register(any(com.example.recommend.controller.dto.RegisterRequest.class)))
                .thenReturn(User.builder().id(1L).email("user@example.com").nickname("tester")
                        .password("sensitive-hash").phoneVerifyCode("123456").phone("01012345678").role("ADMIN").build());
        mvc.perform(post("/users").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.phoneVerifyCode").doesNotExist());
    }

    @Test void duplicateAccountReturns409() throws Exception {
        when(service.register(any(com.example.recommend.controller.dto.RegisterRequest.class)))
                .thenThrow(new ConflictException("이미 사용 중인 이메일입니다."));
        mvc.perform(post("/users").contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
    }

    @Test void invalidRegistrationReturns400() throws Exception {
        when(service.register(any(com.example.recommend.controller.dto.RegisterRequest.class)))
                .thenThrow(new IllegalArgumentException("잘못된 입력"));
        mvc.perform(post("/users").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void forbiddenAndMissingResourceUseDistinctStatuses() throws Exception {
        when(service.register(any(com.example.recommend.controller.dto.RegisterRequest.class)))
                .thenThrow(new ForbiddenException("휴대폰 인증이 필요합니다."))
                .thenThrow(new ResourceNotFoundException("사용자 없음"));
        mvc.perform(post("/users").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/users").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test void integrityConflictDoesNotExposeDatabaseDetails() throws Exception {
        when(service.register(any(com.example.recommend.controller.dto.RegisterRequest.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("sensitive database details"));
        mvc.perform(post("/users").contentType("application/json").content("{}"))
                .andExpect(status().isConflict())
                .andExpect(content().string("기존 데이터와 충돌하는 요청입니다."));
    }
}
