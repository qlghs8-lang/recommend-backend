package com.example.recommend.controller;

import com.example.recommend.domain.Content;
import com.example.recommend.service.ContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;

    @GetMapping("/contents/trending")
    public List<Content> trending(@RequestParam(defaultValue = "10") int size) {
        return contentService.getTrending(size);
    }

    @GetMapping("/contents/new")
    public List<Content> newest(@RequestParam(defaultValue = "10") int size) {
        return contentService.getNewReleases(size);
    }

    @GetMapping("/contents/top-rated")
    public List<Content> topRated(@RequestParam(defaultValue = "10") int size) {
        return contentService.getTopRated(size);
    }

    // ✅ 상세
    @GetMapping("/contents/{id}")
    public Content detail(@PathVariable Long id) {
        return contentService.getById(id);
    }

    // ✅ 검색 (HomePage가 호출)
    @GetMapping("/contents/search")
    public Page<Content> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String genre,

            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,

            @RequestParam(defaultValue = "viewCount") String sort,
            @RequestParam(defaultValue = "desc") String direction
    ) {
        return contentService.search(q, type, genre, page, size, sort, direction);
    }

    // ✅ NEW: 장르 목록 (검색창/온보딩 옵션 동적화에 사용)
    @GetMapping("/contents/genres")
    public List<String> genres() {
        return contentService.getAllGenres();
    }
}
