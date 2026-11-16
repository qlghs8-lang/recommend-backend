package com.example.recommend.controller;

import com.example.recommend.config.JwtUtil;
import com.example.recommend.domain.User;
import com.example.recommend.exception.GlobalExceptionHandler;
import com.example.recommend.repository.*;
import com.example.recommend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PhoneFlowTest {
    String code;
    final String token = "a".repeat(64), phone = "01012345678";
    final UserRepository users = mock(UserRepository.class);
    final PhoneVerificationService phones = new PhoneVerificationService((p, c) -> code = c);
    final UserService service = new UserService(users, mock(PasswordEncoder.class),
            mock(NicknameBlacklistRepository.class), mock(TermsAgreementRepository.class), phones, mock(RelatedDataDeletion.class));
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PublicPhoneController(phones),
            new UserController(service, mock(JwtUtil.class), mock(com.example.recommend.service.ProfileImageStorage.class)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    final ObjectMapper json = new ObjectMapper();
    String requestPublic() throws Exception {
        var result = mvc.perform(post("/public/phone/request").contentType("application/json")
            .content(json.writeValueAsString(Map.of("phone", phone, "requesterToken", token))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(jsonPath("$.length()").value(3)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("challengeId").asText();
    }
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void publicRequestVerifyRegisterAndReplayContract() throws Exception {
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        String id = requestPublic();
        var verified = mvc.perform(post("/public/phone/verify").contentType("application/json")
            .content(json.writeValueAsString(Map.of("phone", phone, "requesterToken", token, "challengeId", id, "code", code))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").doesNotExist()).andReturn();
        String ticket = json.readTree(verified.getResponse().getContentAsString()).get("verificationTicket").asText();
        Map<String, Object> body = new HashMap<>(Map.of("email", "test@example.com", "password", "password123",
            "nickname", "tester", "phone", phone, "phoneRequesterToken", token, "phoneVerificationTicket", ticket,
            "termsAgreements", Map.of("service", true, "privacy", true, "age14", true)));
        body.put("phoneRequesterToken", "b".repeat(64));
        mvc.perform(post("/users").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        verify(users, never()).save(any());
        body.put("phoneRequesterToken", token);
        mvc.perform(post("/users").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk());
        verify(users).save(argThat(u -> Boolean.TRUE.equals(u.getPhoneVerified()) && phone.equals(u.getVerifiedPhone())));
        mvc.perform(post("/users").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        verify(users, times(1)).save(any());
    }

    @Test void accountRequestDoesNotMutateUserAndVerifyBindsAccount() throws Exception {
        User user = User.builder().id(1L).email("a@example.com").phone("01099998888")
            .verifiedPhone("01099998888").phoneVerified(true).build();
        User other = User.builder().id(2L).email("b@example.com").build();
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findByEmail(other.getEmail())).thenReturn(Optional.of(other));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getEmail(), null));
        var result = mvc.perform(post("/user/phone/request").contentType("application/json").content("{\"phone\":\"" + phone + "\"}"))
            .andExpect(status().isOk()).andReturn();
        assertEquals("01099998888", user.getVerifiedPhone());
        verify(users, never()).save(any());
        String id = json.readTree(result.getResponse().getContentAsString()).get("challengeId").asText();
        String body = json.writeValueAsString(Map.of("phone", phone, "challengeId", id, "code", code));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(other.getEmail(), null));
        mvc.perform(post("/user/phone/verify").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getEmail(), null));
        mvc.perform(post("/user/phone/verify").contentType("application/json").content(body)).andExpect(status().isOk());
        assertEquals(phone, user.getVerifiedPhone());
        mvc.perform(post("/user/phone/verify").contentType("application/json").content(body)).andExpect(status().isBadRequest());
    }

    @Test void registrationWithoutPhoneRemainsOptionalAndPhoneWithoutProofFails() throws Exception {
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        var body = new HashMap<String,Object>(Map.of("email", "test@example.com", "password", "password123",
            "nickname", "tester", "termsAgreements", Map.of("service", true, "privacy", true, "age14", true)));
        mvc.perform(post("/users").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk());
        body.put("phone", phone);
        mvc.perform(post("/users").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        verify(users, times(1)).save(any());
    }

    @Test void duplicateVerifiedPhoneDoesNotReplaceExistingAccountPhone() {
        User user = User.builder().id(1L).phone("01099998888").verifiedPhone("01099998888")
                .phoneVerified(true).build();
        when(users.findByEmail("a")).thenReturn(Optional.of(user));
        when(users.existsByVerifiedPhone(phone)).thenReturn(true);
        String id = service.requestPhoneVerification("a", phone, "ip").challengeId();
        assertThrows(com.example.recommend.exception.ConflictException.class,
                () -> service.verifyPhoneCode("a", phone, id, code));
        assertEquals("01099998888", user.getPhone());
        assertEquals("01099998888", user.getVerifiedPhone());
        verify(users, never()).save(any());
    }

    @Test void savingSamePhonePreservesVerificationAndClearingRevokesIt() {
        User user = User.builder().phone(phone).phoneVerified(true).verifiedPhone(phone).build();
        when(users.findByEmail("a")).thenReturn(Optional.of(user));
        var request = new com.example.recommend.dto.user.UserExtraInfoRequest();
        request.setPhone("010-1234-5678");
        service.updateExtraInfo("a", request);
        assertTrue(user.getPhoneVerified());
        request.setPhone(null);
        service.updateExtraInfo("a", request);
        assertFalse(user.getPhoneVerified());
        assertNull(user.getVerifiedPhone());
        assertNull(user.getPhone());
    }
}
