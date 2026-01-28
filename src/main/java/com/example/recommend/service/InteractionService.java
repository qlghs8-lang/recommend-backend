package com.example.recommend.service;

import com.example.recommend.domain.*;
import com.example.recommend.repository.UserContentInteractionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InteractionService {

    private final UserContentInteractionRepository interactionRepo;
    private final ContentService contentService;

    @Transactional
    public InteractionStateResponse view(User user, Long contentId) {
        Content content = contentService.getById(contentId);

        interactionRepo.save(UserContentInteraction.builder()
                .user(user)
                .content(content)
                .eventType(InteractionType.VIEW)
                .createdAt(LocalDateTime.now())
                .build());

        long current = content.getViewCount() == null ? 0 : content.getViewCount();
        content.setViewCount(current + 1);
        contentService.save(content);

        return getState(user, contentId);
    }

    @Transactional
    public InteractionStateResponse like(User user, Long contentId) {
        Content content = contentService.getById(contentId);

        // LIKE 누르면 DISLIKE 삭제
        interactionRepo.deleteByUserIdAndContentIdAndEventType(user.getId(), contentId, InteractionType.DISLIKE);

        // LIKE upsert
        interactionRepo.findByUser_IdAndContent_IdAndEventType(user.getId(), contentId, InteractionType.LIKE)
                .orElseGet(() -> interactionRepo.save(UserContentInteraction.builder()
                        .user(user)
                        .content(content)
                        .eventType(InteractionType.LIKE)
                        .createdAt(LocalDateTime.now())
                        .build()));

        return getState(user, contentId);
    }

    @Transactional
    public InteractionStateResponse dislike(User user, Long contentId) {
        Content content = contentService.getById(contentId);

        // DISLIKE 누르면 LIKE 삭제
        interactionRepo.deleteByUserIdAndContentIdAndEventType(user.getId(), contentId, InteractionType.LIKE);

        // DISLIKE upsert
        interactionRepo.findByUser_IdAndContent_IdAndEventType(user.getId(), contentId, InteractionType.DISLIKE)
                .orElseGet(() -> interactionRepo.save(UserContentInteraction.builder()
                        .user(user)
                        .content(content)
                        .eventType(InteractionType.DISLIKE)
                        .createdAt(LocalDateTime.now())
                        .build()));

        return getState(user, contentId);
    }

    @Transactional
    public InteractionStateResponse toggleBookmark(User user, Long contentId) {
        Content content = contentService.getById(contentId);

        var existing = interactionRepo.findByUser_IdAndContent_IdAndEventType(
                user.getId(), contentId, InteractionType.BOOKMARK
        );

        if (existing.isPresent()) {
            interactionRepo.deleteById(existing.get().getId());
        } else {
            interactionRepo.save(UserContentInteraction.builder()
                    .user(user)
                    .content(content)
                    .eventType(InteractionType.BOOKMARK)
                    .createdAt(LocalDateTime.now())
                    .build());
        }

        return getState(user, contentId);
    }

    @Transactional
    public InteractionStateResponse getState(User user, Long contentId) {
        List<InteractionType> types = interactionRepo.findTypesForContent(
                user.getId(),
                contentId,
                List.of(InteractionType.LIKE, InteractionType.DISLIKE, InteractionType.BOOKMARK)
        );

        Content content = contentService.getById(contentId);
        long viewCount = content.getViewCount() == null ? 0 : content.getViewCount();

        return new InteractionStateResponse(
                types.contains(InteractionType.LIKE),
                types.contains(InteractionType.DISLIKE),
                types.contains(InteractionType.BOOKMARK),
                viewCount
        );
    }

    public record InteractionStateResponse(boolean liked, boolean disliked, boolean bookmarked, long viewCount) {}
}
