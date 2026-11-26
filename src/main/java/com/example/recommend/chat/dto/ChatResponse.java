package com.example.recommend.chat.dto;

public record ChatResponse(String message, Usage usage, boolean catalogGrounded,
        @com.fasterxml.jackson.annotation.JsonIgnore java.util.List<Long> contentIds,
        @com.fasterxml.jackson.annotation.JsonIgnore com.example.recommend.chat.domain.RecommendationProvenance provenance) {
    public ChatResponse(String message, Usage usage, boolean catalogGrounded, java.util.List<Long> contentIds) {
        this(message, usage, catalogGrounded, contentIds, null);
    }
    public ChatResponse(String message, Usage usage, boolean catalogGrounded) {
        this(message, usage, catalogGrounded, java.util.List.of());
    }
    public ChatResponse {
        contentIds = java.util.List.copyOf(contentIds);
        if (contentIds.size() > 5 || contentIds.stream().anyMatch(id -> id <= 0)
                || new java.util.HashSet<>(contentIds).size() != contentIds.size())
            throw new IllegalArgumentException("Invalid public catalog references");
        if (provenance != null && !provenance.ids().equals(contentIds))
            throw new IllegalArgumentException("Invalid recommendation provenance references");
    }
    public record Usage(long inputTokens, long outputTokens, long totalTokens) { }
}
