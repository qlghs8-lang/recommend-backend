package com.example.recommend.service;

import com.example.recommend.domain.Content;
import com.example.recommend.repository.ContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentService {

    private final ContentRepository contentRepository;

    @Transactional(readOnly = true)
    public Content getById(Long id) {
        return contentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Content not found: " + id));
    }

    @Transactional
    public Content save(Content content) {
        return contentRepository.save(content);
    }

    // =========================
    // 홈: 트렌딩/최신/평점
    // =========================
    @Transactional(readOnly = true)
    public List<Content> getTrending(int size) {
        return contentRepository.findByOrderByViewCountDesc(PageRequest.of(0, size));
    }

    @Transactional(readOnly = true)
    public List<Content> getNewReleases(int size) {
        return contentRepository.findByOrderByReleaseDateDesc(PageRequest.of(0, size));
    }

    @Transactional(readOnly = true)
    public List<Content> getTopRated(int size) {
        return contentRepository.findByOrderByRatingDesc(PageRequest.of(0, size));
    }

    // =========================
    // 홈: 검색(페이징/정렬/필터)
    // =========================
    @Transactional(readOnly = true)
    public Page<Content> search(
            String q,
            String type,
            String genre,
            int page,
            int size,
            String sort,
            String direction
    ) {
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;

        String sortField = switch (sort == null ? "" : sort.trim()) {
            case "releaseDate" -> "releaseDate";
            case "rating" -> "rating";
            case "viewCount" -> "viewCount";
            default -> "id";
        };

        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sortField));
        return contentRepository.searchContents(q, type, genre, pageable);
    }

    @Transactional(readOnly = true)
    public List<String> getAllGenres() {
        List<String> csvs = contentRepository.findAllGenresCsv();
        if (csvs == null || csvs.isEmpty()) return List.of();

        Set<String> set = new HashSet<>();
        for (String csv : csvs) {
            if (csv == null || csv.isBlank()) continue;

            String[] parts = csv.split(",");
            for (String p : parts) {
                if (p == null) continue;
                String g = p.trim().toLowerCase();
                if (!g.isBlank()) set.add(g);
            }
        }

        return set.stream()
                .sorted()
                .collect(Collectors.toList());
    }
}

