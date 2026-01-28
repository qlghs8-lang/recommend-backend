package com.example.recommend.admin.controller;

import com.example.recommend.admin.dto.AdminContentRequest;
import com.example.recommend.admin.dto.AdminContentResponse;
import com.example.recommend.admin.service.AdminContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/contents")
public class AdminContentController {

    private final AdminContentService adminContentService;

    /**
     * ✅ 목록(페이징 + 검색/필터 + 정렬)
     * 예)
     * /api/admin/contents?page=0&size=20&q=star&type=MOVIE&genre=action&sort=viewCount&direction=desc
     */
    @GetMapping
    public Page<AdminContentResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,

            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String genre,

            @RequestParam(required = false, defaultValue = "id") String sort,
            @RequestParam(required = false, defaultValue = "desc") String direction
    ) {
        return adminContentService.list(page, size, q, type, genre, sort, direction);
    }

    @GetMapping("/{id}")
    public AdminContentResponse get(@PathVariable Long id) {
        return adminContentService.get(id);
    }

    @PostMapping
    public AdminContentResponse create(@RequestBody AdminContentRequest req) {
        return adminContentService.create(req);
    }

    @PutMapping("/{id}")
    public AdminContentResponse update(@PathVariable Long id, @RequestBody AdminContentRequest req) {
        return adminContentService.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        adminContentService.delete(id);
    }
}
