package com.example.recommend.service;

import com.example.recommend.domain.Content;
import com.example.recommend.repository.ContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ContentService {

    private final ContentRepository contentRepository;

    @Transactional
    public Content create(Content content) {
        return contentRepository.save(content);
    }

    @Transactional(readOnly = true)
    public List<Content> findAll() {
        return contentRepository.findAll();
    }

    @Transactional
    public Content update(Long id, Content newData) {
        Content content = contentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Content not found"));
        content.setTitle(newData.getTitle());
        content.setGenre(newData.getGenre());
        return contentRepository.save(content);
    }

    @Transactional
    public void delete(Long id) {
        contentRepository.deleteById(id);
    }
}
