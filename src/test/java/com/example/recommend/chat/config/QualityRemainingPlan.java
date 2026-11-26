package com.example.recommend.chat.config;

import java.nio.file.*;
import java.util.*;
import static com.example.recommend.chat.config.QualityBaselineTest.*;

/** Evaluation-only guard for a new output directory; never relax the previous launch guards. */
final class QualityRemainingPlan {
    static final Path OUT = Path.of("target/quality-remaining-20261125");
    static final String PROTOCOL = "FIXED_D13_D24_BASELINE_V1";
    static final List<String> CASE_IDS = java.util.stream.IntStream.rangeClosed(13, 24).mapToObj(i -> "D%02d".formatted(i)).toList();

    static Map<String, String> priorPins() {
        var pins = new LinkedHashMap<>(QualityRemeasurementV2Plan.priorPins());
        pins.put("target/quality-remeasurement-v2-20261125/pilot-20261125131458782277600.json",
                "bef8e286be4c6c65028692b0b9a37b3c98d2ded4d578b6c7be7f39bfa6246b79");
        return Map.copyOf(pins);
    }

    static void validateCaps(double prior, double requested, double prepared) {
        if (!Double.isFinite(prior) || prior < 0 || !Double.isFinite(requested) || requested <= 0
                || !Double.isFinite(prepared) || prepared <= 0 || prepared > .30
                || requested > prepared || prior + requested > 1)
            throw new IllegalStateException("Prepared or cumulative ceiling exceeded");
    }
    static void checkNotLaunched(Path out) throws Exception {
        try (var files = Files.list(out)) {
            if (files.anyMatch(p -> p.getFileName().toString().matches("pilot-[0-9]+\\.json")))
                throw new IllegalStateException("Remaining baseline result exists; review before another run");
        }
        // The owning launcher's marker is expected here. Python checks both checkouts before launch.
    }
    static double validate(Path root, Path planPath, double cap) throws Exception {
        if (!QualityRemainingFixture.PIN.equals(sha(root.resolve(QualityRemainingFixture.PATH))))
            throw new IllegalStateException("Synthetic fixture changed");
        var plan = JSON.readTree(Files.readAllBytes(planPath));
        if (!PROTOCOL.equals(plan.get("protocol").asString())
                || !"gpt-6-luna".equals(plan.get("approvedModel").asString())
                || plan.get("approvedCumulativeUsd").asDouble() != 1
                || !CASE_IDS.equals(plan.get("selectedCaseIds").valueStream().map(n -> n.asString()).toList())
                || plan.get("maxProviderRequests").asInt() != 60 || plan.get("sharedTurnOutputTokens").asInt() != 2048
                || plan.get("timeoutSecondsPerTurn").asInt() != 20 || plan.get("retryRequests").asInt() != 0)
            throw new IllegalStateException("Reviewed fixed remaining protocol and bounds required");
        double prior = plan.get("priorReservationEstimateUsd").asDouble();
        validateCaps(prior, cap, plan.get("runMaxEstimatedUsd").asDouble());
        for (var input : plan.get("pinnedInputs")) {
            Path path = Path.of(input.get("path").asString()).normalize();
            if (path.isAbsolute() || path.startsWith("..") || !sha(root.resolve(path)).equals(input.get("sha256").asString()))
                throw new IllegalStateException("Reviewed public input or runner changed");
        }
        var expected = priorPins();
        if (!new HashSet<>(plan.get("priorReports").valueStream().map(n -> n.asString()).toList()).equals(expected.keySet()))
            throw new IllegalStateException("Prior ledger scope changed");
        var found = new HashSet<Path>();
        for (var parent : expected.keySet().stream().map(p -> root.resolve(p).getParent()).distinct().toList()) {
            try (var files = Files.list(parent)) {
                files.filter(p -> p.getFileName().toString().matches("(?:pilot|live)-[0-9]+\\.json")).forEach(found::add);
            }
        }
        if (!found.equals(new HashSet<>(expected.keySet().stream().map(root::resolve).toList())))
            throw new IllegalStateException("Unexpected prior result; review cumulative reservations");
        double computed = 0;
        for (var entry : expected.entrySet()) {
            Path path = root.resolve(entry.getKey());
            if (!sha(path).equals(entry.getValue())) throw new IllegalStateException("Frozen prior report changed");
            var report = JSON.readTree(Files.readAllBytes(path));
            if (!"gpt-6-luna".equals(report.get("model").asString())) throw new IllegalStateException("Prior model changed");
            computed += report.get("reservationEstimateUsd").asDouble();
        }
        if (Math.abs(prior - computed) > 1e-9) throw new IllegalStateException("Cumulative reservation mismatch");
        checkNotLaunched(planPath.getParent());
        return prior;
    }
}
