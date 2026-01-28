package com.example.recommend.controller;

import com.example.recommend.domain.Content;
import com.example.recommend.domain.User;
import com.example.recommend.repository.UserRepository;
import com.example.recommend.service.RecommendService;
import com.example.recommend.service.RecommendService.RecommendItem;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/recommend")
public class RecommendController {

    private final RecommendService recommendService;
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

    // ============================
    // ✅ 기존 API (호환 유지)
    // ============================
    @GetMapping("/for-you")
    public List<ContentResponse> forYou(@RequestParam(defaultValue = "20") int size) {
        List<Content> contents = recommendService.forYou(currentUser(), size);
        return contents.stream().map(ContentResponse::from).toList();
    }

    // ============================
    // ✅ 신규 API: 추천 사유 + recommendLogId 포함
    // ============================
    @GetMapping("/for-you/reason")
    public List<RecommendResponse> forYouWithReason(@RequestParam(defaultValue = "20") int size) {
        List<RecommendItem> items = recommendService.forYouWithReasons(currentUser(), size);
        return items.stream().map(RecommendResponse::from).toList();
    }

    // ============================
    // ✅ NEW: 클릭 로그 저장
    // ============================
    @PostMapping("/click/{recommendLogId}")
    public void click(@PathVariable Long recommendLogId) {
        recommendService.logClick(currentUser(), recommendLogId);
    }

    // ============================
    // DTO
    // ============================

    public record ContentResponse(
            Long id,
            String type,
            String title,
            String overview,
            String genres,
            String posterUrl,
            String backdropUrl,
            Double rating,
            Long ratingCount,
            Long viewCount
    ) {
        public static ContentResponse from(Content c) {
            return new ContentResponse(
                    c.getId(),
                    c.getType(),
                    c.getTitle(),
                    c.getOverview(),
                    c.getGenres(),
                    c.getPosterUrl(),
                    c.getBackdropUrl(),
                    c.getRating(),
                    c.getRatingCount(),
                    c.getViewCount()
            );
        }
    }

    public record RecommendResponse(
            Long id,
            String type,
            String title,
            String overview,
            String genres,
            String posterUrl,
            String backdropUrl,
            Double rating,
            Long ratingCount,
            Long viewCount,
            String reason,
            String source,
            Long recommendLogId
    ) {
        public static RecommendResponse from(RecommendItem item) {
            Content c = item.content();
            return new RecommendResponse(
                    c.getId(),
                    c.getType(),
                    c.getTitle(),
                    c.getOverview(),
                    c.getGenres(),
                    c.getPosterUrl(),
                    c.getBackdropUrl(),
                    c.getRating(),
                    c.getRatingCount(),
                    c.getViewCount(),
                    item.reason(),
                    item.source().name(),
                    item.recommendLogId()
            );
        }
    }
}
