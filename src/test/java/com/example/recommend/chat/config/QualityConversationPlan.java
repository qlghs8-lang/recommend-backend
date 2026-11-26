package com.example.recommend.chat.config;

import java.nio.file.*;
import java.util.*;
import static com.example.recommend.chat.config.QualityBaselineTest.*;

/** Fixed test-only conversation scope; never relax the earlier pilot guards. */
final class QualityConversationPlan {
    static final Path OUT = Path.of("target/quality-conversation-20261125");
    static final String PROTOCOL = "FIXED_DC01_DC03_CONVERSATION_V1";
    static final List<String> CASE_IDS = List.of("DC01", "DC02", "DC03");
    static Map<String, String> priorPins() {
        var result = new LinkedHashMap<>(QualityRemainingPlan.priorPins());
        result.put("target/quality-remaining-20261125/pilot-20261125134737482110200.json",
                "3b4062e06d1a37910b0dc400f3eaaeed9295b19147c1191ef512912c1b600462");
        return Map.copyOf(result);
    }
    static void caps(double prior, double requested, double prepared) {
        if (!Double.isFinite(prior) || prior < 0 || !Double.isFinite(requested) || requested <= 0
                || !Double.isFinite(prepared) || prepared <= 0 || prepared > .18
                || requested > prepared || prior + requested > 1)
            throw new IllegalStateException("Prepared or cumulative ceiling exceeded");
    }
    static void notRun(Path output) throws Exception {
        try (var files = Files.list(output)) {
            if (files.anyMatch(p -> p.getFileName().toString().matches("pilot-[0-9]+\\.json")))
                throw new IllegalStateException("Conversation result exists; preserve before another run");
        }
        // Owning launcher marker is expected; Python checks both checkouts before creating it.
    }
    static double validate(Path root, Path planFile, double cap) throws Exception {
        var p = JSON.readTree(Files.readAllBytes(planFile));
        if (!PROTOCOL.equals(p.get("protocol").asString()) || !"gpt-6-luna".equals(p.get("approvedModel").asString())
                || p.get("approvedCumulativeUsd").asDouble() != 1
                || !CASE_IDS.equals(p.get("selectedCaseIds").valueStream().map(n -> n.asString()).toList())
                || p.get("selectedTurns").asInt() != 12 || p.get("maxProviderRequests").asInt() != 60
                || p.get("sharedTurnOutputTokens").asInt() != 2048 || p.get("timeoutSecondsPerTurn").asInt() != 20
                || p.get("retryRequests").asInt() != 0 || p.get("maxRunSeconds").asInt() != 300
                || !"ISOLATED_H2_ACTUAL_HISTORY_SERVICE".equals(p.get("persistence").asString()))
            throw new IllegalStateException("Fixed conversation protocol required");
        double prior = p.get("priorReservationEstimateUsd").asDouble();
        caps(prior, cap, p.get("runMaxEstimatedUsd").asDouble());
        if (Math.abs(prior + p.get("runMaxEstimatedUsd").asDouble() - p.get("cumulativeMaxEstimatedUsd").asDouble()) > 1e-9)
            throw new IllegalStateException("Cumulative ceiling mismatch");
        for (var pin : p.get("pinnedInputs")) {
            Path rel = Path.of(pin.get("path").asString()).normalize();
            if (rel.isAbsolute() || rel.startsWith("..") || !sha(root.resolve(rel)).equals(pin.get("sha256").asString()))
                throw new IllegalStateException("Prepared input changed");
        }
        var expected = priorPins();
        var listed = p.get("priorReports").valueStream().map(n -> n.asString()).toList();
        if (listed.size() != expected.size() || !new HashSet<>(listed).equals(expected.keySet()))
            throw new IllegalStateException("Ledger scope changed");
        var discovered = new HashSet<Path>();
        for (Path parent : expected.keySet().stream().map(s -> root.resolve(s).getParent()).distinct().toList()) {
            try (var files = Files.list(parent)) {
                files.filter(f -> f.getFileName().toString().matches("(?:pilot|live)-[0-9]+\\.json")).forEach(discovered::add);
            }
        }
        if (!discovered.equals(new HashSet<>(expected.keySet().stream().map(root::resolve).toList())))
            throw new IllegalStateException("Unexpected prior result");
        double sum = 0;
        for (var entry : expected.entrySet()) {
            Path f = root.resolve(entry.getKey());
            if (!sha(f).equals(entry.getValue())) throw new IllegalStateException("Frozen report changed");
            var report = JSON.readTree(Files.readAllBytes(f));
            if (!"gpt-6-luna".equals(report.get("model").asString())) throw new IllegalStateException("Prior model changed");
            sum += report.get("reservationEstimateUsd").asDouble();
        }
        if (Math.abs(prior - sum) > 1e-9) throw new IllegalStateException("Prior reservation mismatch");
        notRun(planFile.getParent());
        return prior;
    }
}
