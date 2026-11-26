package com.example.recommend.chat.config;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Test-only reservation guard; dollar figures are estimates, never an invoice guarantee. */
final class QualityPilotBudget {
    static final int MAX_REQUESTS = 60, MAX_REQUEST_CHARS = 120_000, MAX_TOTAL_CHARS = 600_000;
    static final long MAX_RESERVED_OUTPUT = 122_880;
    static final double INPUT_PER_MILLION = .10, OUTPUT_PER_MILLION = .50;
    final double maxUsd;
    final LongSupplier clock;
    final long deadline;
    int requests, chars, responsesWithUsage;
    long reservedOutput, inputTokens, outputTokens;
    boolean limitReached;

    QualityPilotBudget(double maxUsd) { this(maxUsd, System::nanoTime); }
    QualityPilotBudget(double maxUsd, LongSupplier clock) {
        if (!Double.isFinite(maxUsd) || maxUsd <= 0 || maxUsd > 1) throw new IllegalArgumentException("Pilot ceiling must be (0,1] USD");
        this.maxUsd = maxUsd; this.clock = clock;
        deadline = clock.getAsLong() + Duration.ofMinutes(5).toNanos();
    }
    static double reservedEstimate(int requests, int chars, long output) {
        // Conservative diagnostic proxy: 4 tokens/UTF-16 char plus 16K protocol allowance/request.
        // Not tokenizer-accurate; actual billed usage and unsuccessful requests are reported separately.
        return ((chars * 4L + requests * 16_384L) * INPUT_PER_MILLION + output * OUTPUT_PER_MILLION) / 1_000_000d;
    }
    void reserve(int requestChars, long outputAllowance) {
        if (clock.getAsLong() >= deadline || requests >= MAX_REQUESTS || requestChars < 1
                || requestChars > MAX_REQUEST_CHARS || chars + requestChars > MAX_TOTAL_CHARS
                || outputAllowance < 16 || outputAllowance > 2048
                || reservedOutput + outputAllowance > MAX_RESERVED_OUTPUT
                || reservedEstimate(requests + 1, chars + requestChars, reservedOutput + outputAllowance) > maxUsd) {
            limitReached = true; throw new LimitReached();
        }
        requests++; chars += requestChars; reservedOutput += outputAllowance;
    }
    double observedListPriceEstimate() { return (inputTokens * INPUT_PER_MILLION + outputTokens * OUTPUT_PER_MILLION) / 1_000_000d; }
    static final class LimitReached extends RuntimeException { LimitReached() { super("Fixed pilot budget exhausted"); } }
}
