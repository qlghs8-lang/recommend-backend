package com.example.recommend.controller;

import com.example.recommend.domain.User;
import com.example.recommend.repository.UserRepository;
import com.example.recommend.service.InteractionService;
import com.example.recommend.service.InteractionService.InteractionStateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/interactions")
public class InteractionController {

    private final InteractionService interactionService;
    private final UserRepository userRepository;

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new RuntimeException("Unauthorized");
        }

        String email = authentication.getName();

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @PostMapping("/{contentId}/view")
    public InteractionStateResponse view(@PathVariable Long contentId) {
        return interactionService.view(currentUser(), contentId);
    }

    @PostMapping("/{contentId}/like")
    public InteractionStateResponse like(@PathVariable Long contentId) {
        return interactionService.like(currentUser(), contentId);
    }

    @PostMapping("/{contentId}/dislike")
    public InteractionStateResponse dislike(@PathVariable Long contentId) {
        return interactionService.dislike(currentUser(), contentId);
    }

    @PostMapping("/{contentId}/bookmark")
    public InteractionStateResponse bookmark(@PathVariable Long contentId) {
        return interactionService.toggleBookmark(currentUser(), contentId);
    }

    @GetMapping("/{contentId}/state")
    public InteractionStateResponse state(@PathVariable Long contentId) {
        return interactionService.getState(currentUser(), contentId);
    }
}
