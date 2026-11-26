package com.example.recommend.chat.config;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.recommend.chat.config.QualityBaselineTest.*;

class QualityRemainingPlanTest {
    @Test void lowerPreparedAndCumulativeCeilingsCannotBeRaised() {
        QualityRemainingPlan.validateCaps(.6292521, .30, .30);
        QualityRemainingPlan.validateCaps(.6292521, .01, .01);
        assertThrows(IllegalStateException.class, () -> QualityRemainingPlan.validateCaps(.6292521, .011, .01));
        assertThrows(IllegalStateException.class, () -> QualityRemainingPlan.validateCaps(.99, .02, .30));
        for (double cap : new double[]{0, -.1, .31, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalStateException.class, () -> QualityRemainingPlan.validateCaps(.6292521, cap, .30));
        assertThrows(QualityPilotBudget.LimitReached.class, () -> {
            var budget = new QualityPilotBudget(.30);
            for (int i = 0; i < 60; i++) budget.reserve(10_000, 2048);
        });
    }
    @Test void remainingCasesAndOutputDoNotRepeatTheFirstTwelve() throws Exception {
        String old = System.getProperty("qualityBaselineRemaining");
        try {
            System.setProperty("qualityBaselineRemaining", "true");
            assertEquals(QualityRemainingPlan.OUT, QualityBaselineLiveTest.remeasurementOut());
            assertEquals(QualityRemainingPlan.CASE_IDS, QualityBaselineLiveTest.pilotCases().stream().map(c -> c.get("id").asString()).toList());
            assertEquals("D13", QualityBaselineLiveTest.pilotCases().get(0).get("id").asString());
            System.setProperty("qualityBaselineRemaining", "false");
            assertEquals(Path.of("target/quality-remeasurement-20261125"), QualityBaselineLiveTest.remeasurementOut());
            assertEquals("D01", QualityBaselineLiveTest.pilotCases().get(0).get("id").asString());
            assertNotEquals(OUT, QualityRemainingPlan.OUT);
        } finally {
            if (old == null) System.clearProperty("qualityBaselineRemaining");
            else System.setProperty("qualityBaselineRemaining", old);
        }
    }
    @Test void conflictingScopeFlagsAreRejected() {
        String a = System.getProperty("qualityBaselineRemaining"), b = System.getProperty("qualityBaselineRemeasurementV2");
        try {
            System.setProperty("qualityBaselineRemaining", "true"); System.setProperty("qualityBaselineRemeasurementV2", "true");
            assertThrows(IllegalStateException.class, QualityBaselineLiveTest::pilotCases);
        } finally {
            if (a == null) System.clearProperty("qualityBaselineRemaining"); else System.setProperty("qualityBaselineRemaining", a);
            if (b == null) System.clearProperty("qualityBaselineRemeasurementV2"); else System.setProperty("qualityBaselineRemeasurementV2", b);
        }
    }
    @Test void anExistingV2PilotBlocksAnotherCallButTheOwningLauncherMarkerIsPermitted(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("launch-started.json"), "{}");
        QualityRemainingPlan.checkNotLaunched(temp);
        Path result = temp.resolve("pilot-123.json"); Files.writeString(result, "{\"original\":true}");
        assertThrows(IllegalStateException.class, () -> QualityRemainingPlan.checkNotLaunched(temp));
        assertEquals("{\"original\":true}", Files.readString(result));
    }
    @Test @EnabledIfSystemProperty(named = "qualityRemainingPrepared", matches = "true")
    @SuppressWarnings("unchecked")
    void preparedLedgerPinsScopeAndLowerCapAreValidatedBeforeAnyProviderCall(@TempDir Path temp) throws Exception {
        Path original = QualityRemainingPlan.OUT.resolve("run-plan.json");
        var plan = JSON.readTree(Files.readAllBytes(original));
        double cap = plan.get("runMaxEstimatedUsd").asDouble();
        assertEquals(.6292521, QualityRemainingPlan.validate(Path.of(""), original, cap), 1e-9);
        var lower = (java.util.Map<String, Object>) JSON.readValue(Files.readAllBytes(original), java.util.Map.class);
        double small = Math.min(.05, cap); lower.put("runMaxEstimatedUsd", small);
        Path copy = temp.resolve("plan.json"); Files.write(copy, JSON.writeValueAsBytes(lower));
        assertEquals(.6292521, QualityRemainingPlan.validate(Path.of(""), copy, small), 1e-9);
        assertThrows(IllegalStateException.class, () -> QualityRemainingPlan.validate(Path.of(""), copy, small + .001));
        lower.put("priorReservationEstimateUsd", 0); Files.write(copy, JSON.writeValueAsBytes(lower));
        assertThrows(IllegalStateException.class, () -> QualityRemainingPlan.validate(Path.of(""), copy, small));
        lower.put("priorReservationEstimateUsd", .6292521); lower.put("protocol", "FIXED_D13_D24_INVALID");
        Files.write(copy, JSON.writeValueAsBytes(lower));
        assertThrows(IllegalStateException.class, () -> QualityRemainingPlan.validate(Path.of(""), copy, small));
    }
}
