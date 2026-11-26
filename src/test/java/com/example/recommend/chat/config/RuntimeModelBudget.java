package com.example.recommend.chat.config;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Test-only reservation before every real SDK request, including failed/no-usage requests. */
final class RuntimeModelBudget {
    static final int MAX_REQUESTS = 28, MAX_REQUEST_CHARS = 120_000, MAX_TOTAL_CHARS = 600_000;
    static final long MAX_RESERVED_OUTPUT = 22_400;
    private final LongSupplier clock;
    private final long deadline;
    int requests, chars;
    long reservedOutput, inputTokens, outputTokens;

    RuntimeModelBudget() { this(System::nanoTime); }
    RuntimeModelBudget(LongSupplier clock) {
        this.clock = clock; deadline = clock.getAsLong() + Duration.ofMinutes(3).toNanos();
    }
    void reserve(int requestChars, long outputAllowance) {
        if (clock.getAsLong() >= deadline || requests >= MAX_REQUESTS || requestChars < 1
                || requestChars > MAX_REQUEST_CHARS || chars + requestChars > MAX_TOTAL_CHARS
                || outputAllowance < 16 || outputAllowance > 800
                || reservedOutput + outputAllowance > MAX_RESERVED_OUTPUT)
            throw new LimitReached();
        requests++; chars += requestChars; reservedOutput += outputAllowance;
    }
    static final class LimitReached extends RuntimeException {
        LimitReached() { super("Fixed runtime evaluation budget exhausted"); }
    }
}
