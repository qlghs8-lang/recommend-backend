package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.ChatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit paid-provider opt-in; no Spring context, application configuration or user database. */
@EnabledIfSystemProperty(named = "chatQualityLive", matches = "true")
class ConversationQualityLiveTest {
    @Test void evaluatesRealClassifierAgainstSyntheticConversationCorpus() throws Exception {
        // Dedicated opt-in only; no production properties, database or real conversations.
        var properties = new AiProperties();
        properties.setEnabled(true);
        properties.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY"));
        properties.setModel(System.getenv("CHAT_EVAL_MODEL"));
        properties.validate();
        var all = ConversationQualityCorpus.load();
        String category = System.getProperty("chatQualityCategory", "all");
        var selected = all.stream().filter(f -> category.equals("all") || category.equals(f.category())).toList();
        assertFalse(selected.isEmpty(), "Unknown evaluation category");
        int limit = bounded("chatQualityLimit", 12, 1, all.size());
        int repeats = bounded("chatQualityRepeats", 1, 1, 3);
        var fixtures = selected.stream().limit(limit).toList();
        var results = new ArrayList<Map<String, Object>>();
        var started = Instant.now();
        boolean recordResponses = Boolean.getBoolean("chatQualityRecordSyntheticResponses");
        try {
            if (recordResponses) {
                try (var trace = new SyntheticClassifierTrace(properties, AiConfig.createSdk(properties, "https://api.openai.com/v1"))) {
                    run(trace.client, properties, fixtures, repeats, results, () -> Thread.sleep(1000), trace.items);
                }
            } else try (var client = new AiConfig().aiClient(properties)) {
                run(client, properties, fixtures, repeats, results, () -> Thread.sleep(1000));
            }
        } finally {
            var report = new LinkedHashMap<String, Object>();
            report.put("evaluation", "classification-and-router-contract-only");
            report.put("startedAt", started.toString()); report.put("finishedAt", Instant.now().toString());
            report.put("model", properties.getModel()); report.put("corpusSha256", ConversationQualityCorpus.fingerprint());
            report.put("corpusSize", all.size()); report.put("selectedCases", fixtures.size()); report.put("repeats", repeats);
            report.put("maxAttempts", fixtures.size() * repeats);
            report.put("productionMaxOutputTokens", properties.getMaxOutputTokens());
            report.put("classificationMaxOutputTokens", new TurnBudget(properties).classificationTokens()); report.put("timeoutSeconds", properties.getTimeoutSeconds());
            report.put("fullCorpus", fixtures.size() == all.size());
            report.put("syntheticProviderTraceRecorded", recordResponses);
            report.put("results", results);
            report.put("categories", summaries(results));
            Path directory = Path.of("target", "chat-quality");
            Files.createDirectories(directory);
            String timestamp = started.toString().replaceAll("[^0-9]", "");
            Files.writeString(directory.resolve("live-" + timestamp + ".json"),
                    ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        long passed = results.stream().filter(row -> row.get("status").equals("PASS")).count();
        assertEquals(fixtures.size() * repeats, results.size(), "Incomplete evaluation; inspect sanitized target/chat-quality report");
        // Initial regression gate: every selected critical AND noncritical case must pass.
        assertEquals(results.size(), passed, "Quality gate failed; inspect sanitized target/chat-quality report");
    }

    @FunctionalInterface interface Pause { void await() throws InterruptedException; }

    static void run(AiClient client, AiProperties properties, List<ConversationQualityCorpus.Fixture> fixtures,
            int repeats, List<Map<String, Object>> results, Pause pause) throws InterruptedException {
        run(client, properties, fixtures, repeats, results, pause, null);
    }

    static void run(AiClient client, AiProperties properties, List<ConversationQualityCorpus.Fixture> fixtures,
            int repeats, List<Map<String, Object>> results, Pause pause, List<Map<String, Object>> trace) throws InterruptedException {
        boolean circuitOpen = false;
        for (int repetition = 1; repetition <= repeats; repetition++) {
            for (var fixture : fixtures) {
                var row = new LinkedHashMap<String, Object>();
                row.put("id", fixture.id()); row.put("category", fixture.category());
                row.put("critical", fixture.critical()); row.put("repetition", repetition);
                var budget = new TurnBudget(properties);
                long start = System.nanoTime();
                String status = "NOT_RUN";
                int traceStart = trace == null ? 0 : trace.size();
                if (!circuitOpen) {
                    try {
                        var plan = client.classify(fixture.context(), budget);
                        // Opt-in synthetic diagnostics only; no production conversation path uses this runner.
                        if (Boolean.getBoolean("chatQualityRecordSyntheticPlans")) row.put("actualPlan", plan);
                        var verdict = ConversationQualityCorpus.evaluate(fixture, plan);
                        row.put("semanticMatch", verdict.semantic()); row.put("routerAccepted", verdict.executable());
                        status = verdict.passed() ? "PASS" : "MISMATCH";
                    } catch (ChatException e) {
                        // Only fixed server codes; no SDK messages, causes or raw provider output.
                        status = e.code();
                        circuitOpen = e.status() == 429 || e.status() == 503 || e.status() == 504;
                    }
                }
                row.put("status", status);
                row.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
                row.put("inputTokens", budget.usage().inputTokens());
                row.put("outputTokens", budget.usage().outputTokens());
                if (trace != null) row.put("providerTrace", List.copyOf(trace.subList(traceStart, trace.size())));
                results.add(row);
                if (!circuitOpen) pause.await();
            }
        }
    }

    static int bounded(String name, int fallback, int min, int max) {
        int value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException(name + " outside supported range");
        return value;
    }

    static Map<String, Object> summaries(List<Map<String, Object>> results) {
        var groups = new TreeMap<String, Object>();
        for (String category : results.stream().map(r -> (String) r.get("category")).distinct().toList()) {
            var rows = results.stream().filter(r -> r.get("category").equals(category)).toList();
            long passed = rows.stream().filter(r -> r.get("status").equals("PASS")).count();
            var times = rows.stream().filter(r -> !r.get("status").equals("NOT_RUN"))
                    .mapToLong(r -> (Long) r.get("latencyMs")).sorted().toArray();
            groups.put(category, Map.of("selected", rows.size(), "passed", passed,
                    "criticalFailed", rows.stream().filter(r -> r.get("critical").equals(true)
                            && !r.get("status").equals("PASS")).count(),
                    "p95LatencyMs", times.length == 0 ? 0 : times[(int) Math.ceil(times.length * .95) - 1],
                    "inputTokens", rows.stream().mapToLong(r -> (Long) r.get("inputTokens")).sum(),
                    "outputTokens", rows.stream().mapToLong(r -> (Long) r.get("outputTokens")).sum()));
        }
        return groups;
    }
}
