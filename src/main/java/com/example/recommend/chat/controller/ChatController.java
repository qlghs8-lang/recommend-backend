package com.example.recommend.chat.controller;

import com.example.recommend.chat.dto.ChatRequest;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.ChatService;
import com.example.recommend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/messages")
public class ChatController {
    private final ChatService service;
    private final UserRepository users;

    @PostMapping
    public ChatResponse reply(Authentication authentication, @RequestBody ChatRequest request) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
        }
        var user = users.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."));
        return service.reply(user.getId(), request.message());
    }
}
