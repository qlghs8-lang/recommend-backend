package com.example.recommend.controller;

import com.example.recommend.config.JwtUtil;
import com.example.recommend.domain.User;
import com.example.recommend.service.*;
import com.example.recommend.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProfileImageApiTest {
    @TempDir Path root;
    UserService users;
    ProfileImageStorage storage;
    MockMvc mvc;
    @BeforeEach void setup() {
        users = mock(UserService.class);
        storage = new ProfileImageStorage(root.toString());
        mvc = MockMvcBuilders.standaloneSetup(new UserController(users, mock(JwtUtil.class), storage))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.findByEmail("user")).thenReturn(User.builder().email("user").build());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("user", null));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    MockMultipartFile valid() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("file", null, null, bytes.toByteArray());
    }
    @Test void uploadAndDeleteUseSameConfiguredDirectory() throws Exception {
        String url = mvc.perform(multipart("/user/profile-image").file(valid())).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        verify(users).updateProfileImage("user", url);
        when(users.findByEmail("user")).thenReturn(User.builder().profileImageUrl(url).build());
        mvc.perform(delete("/user/profile-image")).andExpect(status().isOk());
        try (var files = Files.list(root.resolve("profile"))) { assertEquals(0, files.count()); }
    }
    @Test void rejectedImageNeverUpdatesUser() throws Exception {
        mvc.perform(multipart("/user/profile-image").file(new MockMultipartFile("file", "x.png", "image/png", "fake".getBytes())))
            .andExpect(status().isBadRequest());
        verify(users, never()).updateProfileImage(any(), any());
    }
    @Test void databaseFailureRemovesNewFile() throws Exception {
        doThrow(new IllegalArgumentException("failed")).when(users).updateProfileImage(any(), any());
        mvc.perform(multipart("/user/profile-image").file(valid())).andExpect(status().isBadRequest());
        try (var files = Files.list(root.resolve("profile"))) { assertEquals(0, files.count()); }
    }
    @Test void databaseFailureOnDeletePreservesFile() throws Exception {
        String url = storage.store(valid());
        when(users.findByEmail("user")).thenReturn(User.builder().profileImageUrl(url).build());
        doThrow(new IllegalArgumentException("failed")).when(users).updateProfileImage("user", null);
        mvc.perform(delete("/user/profile-image")).andExpect(status().isBadRequest());
        try (var files = Files.list(root.resolve("profile"))) { assertEquals(1, files.count()); }
    }
}
