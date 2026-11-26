package com.example.recommend.chat.client;

import com.example.recommend.chat.dto.ChatResponse;
import java.util.List;
import java.util.Map;

public record CatalogSelection(List<Long> contentIds, ChatResponse.Usage usage, Map<Long, CatalogRationale> reasons) {
    public CatalogSelection(List<Long> contentIds, ChatResponse.Usage usage) { this(contentIds, usage, Map.of()); }
    public CatalogSelection { reasons = Map.copyOf(reasons); }
}
