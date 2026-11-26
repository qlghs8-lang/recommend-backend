package com.example.recommend.chat.client;

import com.example.recommend.chat.config.AiProperties;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.ChatException;
import java.time.Duration;

/** One synchronous turn, including classification, retrieval and every provider/tool round. */
public final class TurnBudget {
    private final long deadline;
    private final int maxOutput;
    private long input, output;

    public TurnBudget(AiProperties properties) {
        deadline = System.nanoTime() + Duration.ofSeconds(properties.getTimeoutSeconds()).toNanos();
        maxOutput = properties.getMaxOutputTokens();
    }

    public Duration remainingTime() {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw ChatException.timeout();
        return Duration.ofNanos(remaining);
    }

    public int remainingTokens() {
        remainingTime();
        int remaining = maxOutput - (int) output;
        if (remaining < 16) throw ChatException.invalidResponse();
        return remaining;
    }

    /** Classification gets room for eight conditions while reserving tokens for the answer.
     * This is a per-call ceiling, never an increase of the total turn budget. */
    public int classificationTokens() {
        int remaining = remainingTokens();
        int reserved = Math.min(256, remaining / 3);
        return Math.min(1024, Math.max(16, remaining - reserved));
    }

    public void consume(ChatResponse.Usage usage) {
        remainingTime();
        if (usage == null || usage.inputTokens() < 0 || usage.inputTokens() > 1_000_000
                || usage.outputTokens() < 0 || usage.outputTokens() > maxOutput - output
                || usage.totalTokens() != usage.inputTokens() + usage.outputTokens())
            throw ChatException.invalidResponse();
        input += usage.inputTokens(); output += usage.outputTokens();
    }

    public ChatResponse.Usage usage() { return new ChatResponse.Usage(input, output, input + output); }
}
