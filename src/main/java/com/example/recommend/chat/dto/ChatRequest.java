package com.example.recommend.chat.dto;

public record ChatRequest(String message) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported chat request field");
    }
}
