package com.example.recommend.chat.config;

import java.nio.file.*;
import java.util.*;
import static com.example.recommend.chat.config.QualityBaselineTest.*;

/** Evaluation-only guard for a new output directory; never relax the previous launch guards. */
final class QualityRemeasurementV2Plan {
    static final Path OUT = Path.of("target/quality-remeasurement-v2-20261125");
    static final String PROTOCOL = "FIXED_D01_D12_REMEASUREMENT_V2";
    static final List<String> CASE_IDS = java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(i -> "D%02d".formatted(i)).toList();

    static Map<String, String> priorPins() {
        return Map.of(
            "target/quality-baseline-20261125/pilot-20261125082720913747800.json", "d32f339ab407bd7736705ea17dc429e8736925df1fb3dd5f00264204a731122c",
            "target/schema-d10-diagnosis-20261125/live-20261125084417230961500.json", "eddbf74bdbfafe540f44a29e97d0edcbd61ff84faafbe933c644b0a0a174151f",
            "target/schema-d10-diagnosis-20261125/live-20261125091022637208400.json", "2fc2315aca164710a8a5c2e9443b593f4f22faab5a2aee4c65b08a570f3162d8",
            "target/quality-remeasurement-20261125/pilot-20261125091500585131800.json", "ed69c1e659941ac2fd397a14b5aa44c5462f34f83424ac800a9a90c678f797ec",
            "target/schema-d09-diagnosis-20261125/live-20261125122159875469300.json", "65690b6dc886bf948a3b99acac34c6976fb3476214a8d698f63f788e4a55bbc6",
            "target/quote-schema-acceptance-20261125/live-20261125125148178675400.json", "b3237c771ab59aa27e33bed2b2b49e8fc9cbceb2de4c66e46d13b916fa042712");
    }
    static void validateCaps(double prior, double requested, double prepared) {
        if (!Double.isFinite(prior) || prior < 0 || !Double.isFinite(requested) || requested <= 0
                || !Double.isFinite(prepared) || prepared <= 0 || prepared > .40
                || requested > prepared || prior + requested > 1)
            throw new IllegalStateException("Prepared or cumulative ceiling exceeded");
    }
    static void checkNotLaunched(Path out) throws Exception {
        try (var files = Files.list(out)) {
            if (files.anyMatch(p -> p.getFileName().toString().matches("pilot-[0-9]+\\.json")))
                throw new IllegalStateException("V2 result exists; review before another run");
        }
        // The owning launcher's marker is expected here. Python checks both checkouts before launch.
    }
    static double validate(Path root, Path planPath, double cap) throws Exception {
        var plan = JSON.readTree(Files.readAllBytes(planPath));
        if (!PROTOCOL.equals(plan.get("protocol").asString())
                || !"gpt-6-luna".equals(plan.get("approvedModel").asString())
                || plan.get("approvedCumulativeUsd").asDouble() != 1
                || !CASE_IDS.equals(plan.get("selectedCaseIds").valueStream().map(n -> n.asString()).toList())
                || plan.get("maxProviderRequests").asInt() != 60 || plan.get("sharedTurnOutputTokens").asInt() != 2048
                || plan.get("timeoutSecondsPerTurn").asInt() != 20 || plan.get("retryRequests").asInt() != 0)
            throw new IllegalStateException("Reviewed fixed v2 protocol and bounds required");
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
