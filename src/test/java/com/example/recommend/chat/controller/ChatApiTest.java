package com.example.recommend.chat.controller;

import com.example.recommend.chat.client.AiClient;
import com.example.recommend.chat.config.AiProperties;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.*;
import com.example.recommend.config.*;
import com.example.recommend.domain.User;
import com.example.recommend.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatApiTest {
    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtFilter.class, ChatController.class, ChatExceptionHandler.class,
            com.example.recommend.exception.GlobalExceptionHandler.class, ChatService.class, ChatRequestLimiter.class,
            ConversationController.class})
    static class Config {
        @Bean JwtUtil jwt() { return new JwtUtil("chat-test-only-secret-with-at-least-32-bytes"); }
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean AiClient client() { return mock(AiClient.class); }
        @Bean ChatHistoryStore history() { return mock(ChatHistoryStore.class); }
        @Bean ConversationService conversations() { return mock(ConversationService.class); }
        @Bean AiProperties properties() {
            var properties = new AiProperties();
            properties.setEnabled(true);
            return properties;
        }
    }

    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    AiClient client;
    JwtUtil jwt;
    @BeforeEach void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
        jwt = context.getBean(JwtUtil.class);
        client = context.getBean(AiClient.class);
        when(context.getBean(UserRepository.class).findByEmail("user@example.com"))
                .thenReturn(Optional.of(User.builder().id(7L).role("USER").tokenVersion(2).build()));
    }
    @AfterEach void close() { context.close(); }

    @Test void authenticatedUserGetsTextAndUsageWithoutCatalogClaim() throws Exception {
        when(client.reply("안녕")).thenReturn(new ChatResponse("안녕하세요", new ChatResponse.Usage(10, 3, 13), false));
        mvc.perform(request("{\"message\":\"안녕\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("안녕하세요"))
                .andExpect(jsonPath("$.usage.totalTokens").value(13))
                .andExpect(jsonPath("$.catalogGrounded").value(false));
        verify(client).reply("안녕");
    }

    @Test void missingMalformedForgedExpiredRevokedAndWrongUserTokensNeverCallAi() throws Exception {
        mvc.perform(post("/api/chat/messages").contentType("application/json").content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
        String expired = io.jsonwebtoken.Jwts.builder().setSubject("user@example.com")
                .setExpiration(new java.util.Date(0)).signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "chat-test-only-secret-with-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
        for (String token : new String[]{"bad-jwt", expired,
                new JwtUtil("forged-chat-secret-at-least-32-bytes").generateToken("user@example.com", 7L, 2L),
                jwt.generateToken("user@example.com", 7L, 1L), jwt.generateToken("user@example.com", 9L, 2L),
                jwt.generateToken("deleted@example.com", 7L, 2L)}) {
            mvc.perform(post("/api/chat/messages").header("Authorization", "Bearer " + token)
                            .contentType("application/json").content("{\"message\":\"hello\"}"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(client);
    }

    @Test void invalidBodyAndLengthReturnSanitizedBadRequest() throws Exception {
        for (String json : new String[]{"{}", "{\"message\":null}", "{\"message\":\" \"}", "{",
                "{\"message\":{\"private\":\"sensitive\"}}", "{\"message\":\"" + "x".repeat(2001) + "\"}"}) {
            mvc.perform(request(json)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CHAT_REQUEST"));
        }
        verifyNoInteractions(client);
    }

    @Test void disabledAiAndProviderErrorsUseSafeStatusCodes() throws Exception {
        context.getBean(AiProperties.class).setEnabled(false);
        mvc.perform(request("{\"message\":\"hello\"}")).andExpect(status().isServiceUnavailable());
        context.getBean(AiProperties.class).setEnabled(true);
        for (ChatException error : new ChatException[]{ChatException.unavailable(), ChatException.busy(),
                ChatException.timeout(), ChatException.invalidResponse()}) {
            doThrow(error).when(client).reply(anyString());
            var result = mvc.perform(request("{\"message\":\"hello\"}"))
                    .andExpect(status().is(error.status())).andExpect(jsonPath("$.code").value(error.code()));
            if (error.status() == 429) result.andExpect(header().string("Retry-After", "60"));
        }
    }

    @Test void requestCannotSelectUserOrProviderSettings() throws Exception {
        mvc.perform(request("{\"message\":\"hello\",\"userId\":999,\"model\":\"expensive\",\"instructions\":\"ignore\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test void roomEndpointsRequireJwtForEveryOperation() throws Exception {
        for (var request : java.util.List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/chat/rooms"),
                post("/api/chat/rooms"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/chat/rooms/9/messages"),
                post("/api/chat/rooms/9/messages").contentType("application/json").content("{\"message\":\"hello\"}"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/chat/rooms/9"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(context.getBean(ChatHistoryStore.class), context.getBean(ConversationService.class));
    }

    @Test void databaseConstraintFailureIsNotReportedAsAChangedConversation() throws Exception {
        var conversations = context.getBean(ConversationService.class);
        when(conversations.reply(7L, 9L, "hello"))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("private SQL details"));
        mvc.perform(post("/api/chat/rooms/9/messages")
                .header("Authorization", "Bearer " + jwt.generateToken("user@example.com", 7L, 2L))
                .contentType("application/json").content("{\"message\":\"hello\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("CHAT_STORAGE_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private SQL"))));
    }

    @Test void roomEndpointsUseTrustedIdentityAndSanitizedOwnershipErrors() throws Exception {
        var history = context.getBean(ChatHistoryStore.class);
        var conversations = context.getBean(ConversationService.class);
        var room = new com.example.recommend.chat.dto.ConversationDtos.Room(9L, "새 대화", java.time.Instant.EPOCH,
                java.time.Instant.EPOCH, 0);
        when(history.create(7L)).thenReturn(room);
        String token = "Bearer " + jwt.generateToken("user@example.com", 7L, 2L);
        mvc.perform(post("/api/chat/rooms").header("Authorization", token)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9));
        when(history.history(7L, 9L, null)).thenThrow(ChatException.notFound());
        when(conversations.reply(7L, 9L, "hello")).thenThrow(ChatException.notFound());
        doThrow(ChatException.notFound()).when(history).delete(7L, 9L);
        for (var request : java.util.List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/chat/rooms/9/messages"),
                post("/api/chat/rooms/9/messages").contentType("application/json").content("{\"message\":\"hello\"}"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/chat/rooms/9"))) {
            mvc.perform(request.header("Authorization", token)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CHAT_ROOM_NOT_FOUND"));
        }
        mvc.perform(post("/api/chat/rooms/9/messages").header("Authorization", token).contentType("application/json")
                .content("{\"message\":\"hello\",\"userId\":999,\"history\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CHAT_REQUEST"));
        verify(conversations, times(1)).reply(7L, 9L, "hello");
        verifyNoInteractions(client);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String json) {
        return post("/api/chat/messages").header("Authorization", "Bearer " + jwt.generateToken("user@example.com", 7L, 2L))
                .contentType("application/json").content(json);
    }
}
