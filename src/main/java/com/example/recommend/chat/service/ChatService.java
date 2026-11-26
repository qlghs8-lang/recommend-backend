package com.example.recommend.chat.service;

import com.example.recommend.chat.client.AiClient;
import com.example.recommend.chat.config.AiProperties;
import com.example.recommend.chat.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatService {
    private final AiClient client;
    private final AiProperties properties;
    private final ChatRequestLimiter limiter;

    // Intentionally no transaction: a DB connection must not span network generation.
    public ChatResponse reply(long userId, String message) {
        if (message == null || message.isBlank() || message.length() > properties.getMaxInputChars()) {
            throw new IllegalArgumentException("메시지는 1~" + properties.getMaxInputChars() + "자여야 합니다.");
        }
        if (!properties.isEnabled()) throw ChatException.unavailable();
        limiter.acquire(userId);
        try {
            return client.reply(message.strip());
        } finally {
            limiter.release();
        }
    }
}
