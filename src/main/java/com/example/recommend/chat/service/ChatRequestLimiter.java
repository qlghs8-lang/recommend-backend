package com.example.recommend.chat.service;

import com.example.recommend.chat.config.AiProperties;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Single-instance, bounded sliding windows. Failed provider attempts also consume quota. */
@Component
public class ChatRequestLimiter {
    private static final long WINDOW_NANOS = java.time.Duration.ofMinutes(1).toNanos();
    private final AiProperties properties;
    private final LongSupplier clock;
    private final Map<Long, java.util.ArrayDeque<Long>> users = new HashMap<>();
    private final java.util.ArrayDeque<Long> global = new java.util.ArrayDeque<>();
    private int active;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatRequestLimiter(AiProperties properties) { this(properties, System::nanoTime); }
    ChatRequestLimiter(AiProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized void acquire(long userId) {
        long now = clock.getAsLong();
        users.values().forEach(queue -> expire(queue, now));
        users.values().removeIf(java.util.ArrayDeque::isEmpty);
        expire(global, now);
        if (active >= properties.getMaxConcurrent() || global.size() >= properties.getGlobalRequestsPerMinute()) {
            throw ChatException.busy();
        }
        var queue = users.computeIfAbsent(userId, ignored -> new java.util.ArrayDeque<>());
        if (queue.size() >= properties.getRequestsPerMinute()) throw ChatException.busy();
        queue.addLast(now);
        global.addLast(now);
        active++;
    }

    public synchronized void release() { active--; }

    private void expire(java.util.ArrayDeque<Long> queue, long now) {
        while (!queue.isEmpty() && now - queue.peekFirst() >= WINDOW_NANOS) queue.removeFirst();
    }
}
