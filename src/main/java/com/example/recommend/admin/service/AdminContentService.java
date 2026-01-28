package com.example.recommend.admin.service;

import com.example.recommend.admin.dto.AdminContentRequest;
import com.example.recommend.admin.dto.AdminContentResponse;
import com.example.recommend.domain.Content;
import com.example.recommend.repository.ContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminContentService {

    private final ContentRepository contentRepository;

    @Transactional(readOnly = true)
    public Page<AdminContentResponse> list(
            int page,
            int size,
            String q,
            String type,
            String genre,
            String sort,      // "id" | "releaseDate" | "rating" | "viewCount"
            String direction  // "desc" | "asc"
    ) {
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;

        String sortField = switch (sort == null ? "" : sort.trim()) {
            case "releaseDate" -> "releaseDate";
            case "rating" -> "rating";
            case "viewCount" -> "viewCount";
            default -> "id";
        };

        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sortField));

        return contentRepository.searchAdminContents(q, type, genre, pageable)
                .map(AdminContentResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminContentResponse get(Long id) {
        Content c = contentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Content not found: " + id));
        return AdminContentResponse.from(c);
    }

    @Transactional
    public AdminContentResponse create(AdminContentRequest req) {
        validate(req);

        Content c = Content.builder()
                .type(normType(req.getType()))
                .title(req.getTitle().trim())
                .overview(req.getOverview())
                .genres(req.getGenres())
                .releaseDate(req.getReleaseDate())
                .posterUrl(req.getPosterUrl())
                .backdropUrl(req.getBackdropUrl())
                .rating(req.getRating())
                .ratingCount(req.getRatingCount())
                .viewCount(req.getViewCount() == null ? 0L : req.getViewCount())
                .build();

        return AdminContentResponse.from(contentRepository.save(c));
    }

    @Transactional
    public AdminContentResponse update(Long id, AdminContentRequest req) {
        validate(req);

        Content c = contentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Content not found: " + id));

        c.setType(normType(req.getType()));
        c.setTitle(req.getTitle().trim());
        c.setOverview(req.getOverview());
        c.setGenres(req.getGenres());
        c.setReleaseDate(req.getReleaseDate());
        c.setPosterUrl(req.getPosterUrl());
        c.setBackdropUrl(req.getBackdropUrl());
        c.setRating(req.getRating());
        c.setRatingCount(req.getRatingCount());
        if (req.getViewCount() != null) c.setViewCount(req.getViewCount());

        return AdminContentResponse.from(contentRepository.save(c));
    }

    @Transactional
    public void delete(Long id) {
        if (!contentRepository.existsById(id)) {
            throw new IllegalArgumentException("Content not found: " + id);
        }
        contentRepository.deleteById(id);
    }

    private void validate(AdminContentRequest req) {
        if (req == null) throw new IllegalArgumentException("Request is null");
        if (req.getType() == null || req.getType().isBlank()) throw new IllegalArgumentException("type is required");
        if (req.getTitle() == null || req.getTitle().isBlank()) throw new IllegalArgumentException("title is required");
    }

    private String normType(String type) {
        String t = type.trim().toUpperCase();
        if (!t.equals("MOVIE") && !t.equals("TV")) {
            // 너 Content 엔티티 주석이 MOVIE/TV라 이 둘만 허용
            throw new IllegalArgumentException("type must be MOVIE or TV");
        }
        return t;
    }
}
