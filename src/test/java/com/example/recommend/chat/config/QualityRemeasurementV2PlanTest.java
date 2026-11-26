package com.example.recommend.chat.config;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.recommend.chat.config.QualityBaselineTest.*;

class QualityRemeasurementV2PlanTest {
    @Test void lowerPreparedAndCumulativeCeilingsCannotBeRaised() {
        QualityRemeasurementV2Plan.validateCaps(.3793603, .40, .40);
        QualityRemeasurementV2Plan.validateCaps(.3793603, .01, .01);
        assertThrows(IllegalStateException.class, () -> QualityRemeasurementV2Plan.validateCaps(.3793603, .011, .01));
        assertThrows(IllegalStateException.class, () -> QualityRemeasurementV2Plan.validateCaps(.99, .02, .40));
        for (double cap : new double[]{0, -.1, .41, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalStateException.class, () -> QualityRemeasurementV2Plan.validateCaps(.3793603, cap, .40));
        assertTrue(QualityPilotBudget.reservedEstimate(60, 600_000, 122_880) < .40);
    }
    @Test void v2OutputChoicePreservesTheOriginalAndFirstRemeasurementPaths() {
        String old = System.getProperty("qualityBaselineRemeasurementV2");
        try {
            System.setProperty("qualityBaselineRemeasurementV2", "true");
            assertEquals(QualityRemeasurementV2Plan.OUT, QualityBaselineLiveTest.remeasurementOut());
            System.setProperty("qualityBaselineRemeasurementV2", "false");
            assertEquals(Path.of("target/quality-remeasurement-20261125"), QualityBaselineLiveTest.remeasurementOut());
            assertNotEquals(OUT, QualityRemeasurementV2Plan.OUT);
        } finally {
            if (old == null) System.clearProperty("qualityBaselineRemeasurementV2");
            else System.setProperty("qualityBaselineRemeasurementV2", old);
        }
    }
    @Test void anExistingV2PilotBlocksAnotherCallButTheOwningLauncherMarkerIsPermitted(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("launch-started.json"), "{}");
        QualityRemeasurementV2Plan.checkNotLaunched(temp);
        Path result = temp.resolve("pilot-123.json"); Files.writeString(result, "{\"original\":true}");
        assertThrows(IllegalStateException.class, () -> QualityRemeasurementV2Plan.checkNotLaunched(temp));
        assertEquals("{\"original\":true}", Files.readString(result));
    }
    @Test @EnabledIfSystemProperty(named = "qualityRemeasurementV2Prepared", matches = "true")
    @SuppressWarnings("unchecked")
    void preparedLedgerPinsScopeAndLowerCapAreValidatedBeforeAnyProviderCall(@TempDir Path temp) throws Exception {
        Path original = QualityRemeasurementV2Plan.OUT.resolve("run-plan.json");
        var plan = JSON.readTree(Files.readAllBytes(original));
        double cap = plan.get("runMaxEstimatedUsd").asDouble();
        assertEquals(.3793603, QualityRemeasurementV2Plan.validate(Path.of(""), original, cap), 1e-9);
        var lower = (java.util.Map<String, Object>) JSON.readValue(Files.readAllBytes(original), java.util.Map.class);
        double small = Math.min(.05, cap); lower.put("runMaxEstimatedUsd", small);
        Path copy = temp.resolve("plan.json"); Files.write(copy, JSON.writeValueAsBytes(lower));
        assertEquals(.3793603, QualityRemeasurementV2Plan.validate(Path.of(""), copy, small), 1e-9);
        assertThrows(IllegalStateException.class, () -> QualityRemeasurementV2Plan.validate(Path.of(""), copy, small + .001));
        lower.put("priorReservationEstimateUsd", 0); Files.write(copy, JSON.writeValueAsBytes(lower));
        assertThrows(IllegalStateException.class, () -> QualityRemeasurementV2Plan.validate(Path.of(""), copy, small));
        lower.put("priorReservationEstimateUsd", .3793603); lower.put("protocol", "FIXED_D01_D12_REMEASUREMENT_V1");
        Files.write(copy, JSON.writeValueAsBytes(lower));
        assertThrows(IllegalStateException.class, () -> QualityRemeasurementV2Plan.validate(Path.of(""), copy, small));
    }
}
