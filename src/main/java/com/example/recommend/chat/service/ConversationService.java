package com.example.recommend.chat.service;

import com.example.recommend.chat.config.AiProperties;
import com.example.recommend.chat.dto.ConversationDtos.Turn;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationService {
    private final ChatHistoryStore history;
    private final ChatRouter recommendations;
    private final AiProperties properties;
    private final ChatRequestLimiter limiter;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Turn reply(long userId, long roomId, String message) {
        if (message == null || message.isBlank() || message.length() > properties.getMaxInputChars())
            throw new IllegalArgumentException("Invalid message");
        String question = message.strip();
        var snapshot = history.prepare(userId, roomId, question);
        if (!properties.isEnabled()) throw ChatException.unavailable();
        limiter.acquire(userId);
        try {
            var response = snapshot.provenance().isEmpty() ? recommendations.reply(userId, snapshot.context())
                    : recommendations.reply(userId, snapshot.context(), snapshot.provenance());
            return history.complete(userId, roomId, snapshot, question, response);
        } finally {
            limiter.release();
        }
    }
}
