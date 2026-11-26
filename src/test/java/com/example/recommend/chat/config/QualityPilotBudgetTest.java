package com.example.recommend.chat.config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QualityPilotBudgetTest {
    @Test void remeasurementWorstCaseFitsItsLowerCapAndExistingCumulativeApproval() {
        double worst = QualityPilotBudget.reservedEstimate(QualityPilotBudget.MAX_REQUESTS,
                QualityPilotBudget.MAX_TOTAL_CHARS, QualityPilotBudget.MAX_RESERVED_OUTPUT);
        assertTrue(worst < .40);
        assertTrue(.1952003 + .40 < 1);
        var budget = new QualityPilotBudget(.40);
        for (int i = 0; i < 60; i++) budget.reserve(10_000, 2048);
        assertEquals(60, budget.requests);
        assertThrows(QualityPilotBudget.LimitReached.class, () -> budget.reserve(1, 16));
    }
    @Test void rejectedReservationDoesNotConsumeAnotherRequest() {
        var b = new QualityPilotBudget(1);
        b.reserve(100, 2048);
        assertThrows(QualityPilotBudget.LimitReached.class, () -> b.reserve(120001, 16));
        assertEquals(1, b.requests); assertEquals(2048, b.reservedOutput); assertTrue(b.limitReached);
    }
    @Test void failedRequestsKeepTheirReservationAndSixtyFirstIsRejected() {
        var b = new QualityPilotBudget(1);
        for (int i = 0; i < 60; i++) b.reserve(1, 16);
        assertThrows(QualityPilotBudget.LimitReached.class, () -> b.reserve(1, 16));
        assertEquals(60, b.requests); assertEquals(0, b.responsesWithUsage);
    }
    @Test void totalCharactersAndDollarEstimateStopBeforeNextRequest() {
        var b = new QualityPilotBudget(1);
        for (int i = 0; i < 5; i++) b.reserve(120000, 16);
        assertThrows(QualityPilotBudget.LimitReached.class, () -> b.reserve(1, 16));
        var small = new QualityPilotBudget(.001);
        assertThrows(QualityPilotBudget.LimitReached.class, () -> small.reserve(100, 16));
        assertEquals(0, small.requests);
    }
    @Test void deadlineAndInvalidCapsAreRejected() {
        var clock = new AtomicLong(0); var b = new QualityPilotBudget(1, clock::get);
        clock.set(Duration.ofMinutes(5).toNanos());
        assertThrows(QualityPilotBudget.LimitReached.class, () -> b.reserve(1, 16));
        for (double cap : new double[]{0, -1, 1.1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new QualityPilotBudget(cap));
    }
}
