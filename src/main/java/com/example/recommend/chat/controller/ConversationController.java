package com.example.recommend.chat.controller;

import com.example.recommend.chat.dto.ChatRequest;
import com.example.recommend.chat.dto.ConversationDtos.*;
import com.example.recommend.chat.service.*;
import com.example.recommend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/rooms")
public class ConversationController {
    private final ChatHistoryStore history;
    private final ConversationService service;
    private final UserRepository users;

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Room create(Authentication auth) { return history.create(userId(auth)); }
    @GetMapping
    public Rooms list(Authentication auth, @RequestParam(required = false) Long before) {
        return history.list(userId(auth), before);
    }
    @GetMapping("/{roomId}/messages")
    public History messages(Authentication auth, @PathVariable long roomId,
                            @RequestParam(required = false) Long before) {
        return history.history(userId(auth), roomId, before);
    }
    @PostMapping("/{roomId}/messages")
    public Turn reply(Authentication auth, @PathVariable long roomId, @RequestBody ChatRequest request) {
        return service.reply(userId(auth), roomId, request.message());
    }
    @DeleteMapping("/{roomId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable long roomId) { history.delete(userId(auth), roomId); }

    private long userId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
        return users.findByEmail(auth.getName()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")).getId();
    }
}
