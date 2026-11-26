package com.example.recommend.chat.config;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeModelBudgetTest {
    @Test void failedRequestsStillConsumeReservationsAndCannotExceedCallLimit() {
        var budget = new RuntimeModelBudget(() -> 0);
        for (int i = 0; i < 28; i++) budget.reserve(100, 800);
        assertThrows(RuntimeModelBudget.LimitReached.class, () -> budget.reserve(100, 800));
        assertEquals(28, budget.requests); assertEquals(22_400, budget.reservedOutput);
    }
    @Test void oversizedPublicContextAndCumulativeInputAreRejectedBeforeSending() {
        var budget = new RuntimeModelBudget(() -> 0);
        assertThrows(RuntimeModelBudget.LimitReached.class, () -> budget.reserve(120_001, 800));
        assertEquals(0, budget.requests);
        for (int i = 0; i < 5; i++) budget.reserve(120_000, 800);
        assertThrows(RuntimeModelBudget.LimitReached.class, () -> budget.reserve(1, 800));
        assertEquals(5, budget.requests);
    }
    @Test void noNewRequestStartsAtDeadlineAndPerCallProductionTokenCeilingIsPreserved() {
        var clock = new AtomicLong(); var budget = new RuntimeModelBudget(clock::get);
        assertThrows(RuntimeModelBudget.LimitReached.class, () -> budget.reserve(100, 801));
        clock.set(Duration.ofMinutes(3).toNanos());
        assertThrows(RuntimeModelBudget.LimitReached.class, () -> budget.reserve(100, 800));
        assertEquals(0, budget.requests);
    }
}
