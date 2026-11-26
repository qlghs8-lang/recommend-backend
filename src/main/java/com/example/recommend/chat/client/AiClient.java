package com.example.recommend.chat.client;

import com.example.recommend.chat.dto.ChatResponse;

public interface AiClient extends AutoCloseable {
    default CatalogSelection recommendWithTools(java.util.List<AiMessage> messages, CatalogTools tools, TurnBudget budget) {
        throw com.example.recommend.chat.service.ChatException.unavailable();
    }
    default java.util.Map<Long, CatalogRationale> justifyMany(String originalRequest, String question,
            java.util.List<CatalogCandidate> works, TurnBudget budget) {
        throw com.example.recommend.chat.service.ChatException.unavailable();
    }
    default CatalogRationale justify(String originalRequest, String question, CatalogCandidate work, TurnBudget budget) {
        throw com.example.recommend.chat.service.ChatException.unavailable();
    }
    default ChatResponse explain(String question, TurnBudget budget) {
        throw com.example.recommend.chat.service.ChatException.unavailable();
    }
    default ChatPlan classify(java.util.List<AiMessage> messages, TurnBudget budget) {
        throw com.example.recommend.chat.service.ChatException.unavailable();
    }
    default CatalogSelection selectWithTools(java.util.List<AiMessage> messages, CatalogTools tools, TurnBudget budget) {
        throw com.example.recommend.chat.service.ChatException.unavailable();
    }
    default CatalogSelection selectWithTools(java.util.List<AiMessage> messages, CatalogTools tools) {
        throw com.example.recommend.chat.service.ChatException.unavailable();
    }
    default CatalogSelection select(java.util.List<AiMessage> messages, java.util.List<CatalogCandidate> candidates) {
        throw com.example.recommend.chat.service.ChatException.unavailable();
    }
    ChatResponse reply(java.util.List<AiMessage> messages);
    default ChatResponse reply(String message) {
        return reply(java.util.List.of(new AiMessage(AiMessage.Role.USER, message)));
    }
    @Override default void close() { }
}
