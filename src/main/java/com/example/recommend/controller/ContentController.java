package com.example.recommend.controller;

import com.example.recommend.domain.Content;
import com.example.recommend.service.ContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ContentController {

    private final ContentService contentService;

    @PostMapping("/contents")
    public Content create(@RequestBody Content content) {
        return contentService.create(content);
    }

    @GetMapping("/contents")
    public List<Content> all() {
        return contentService.findAll();
    }

    @PutMapping("/contents/{id}")
    public Content update(@PathVariable Long id, @RequestBody Content content) {
        return contentService.update(id, content);
    }

    @DeleteMapping("/contents/{id}")
    public void delete(@PathVariable Long id) {
        contentService.delete(id);
    }
}
