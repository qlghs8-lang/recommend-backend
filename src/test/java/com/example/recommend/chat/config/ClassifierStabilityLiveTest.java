package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.ChatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Paid opt-in. Exactly 21 frozen synthetic cases x three; no DB or private conversation. */
@EnabledIfSystemProperty(named = "chatStabilityLive", matches = "true")
class ClassifierStabilityLiveTest {
    @Test void evaluatesFrozenConditionsAndCompoundRequestsThreeTimes() throws Exception {
        var p = new AiProperties(); p.setEnabled(true); p.setMaxOutputTokens(2048);
        p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY")); p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        var cases = ClassifierStabilityCorpus.load(); assertEquals(21, cases.size());
        var results = new ArrayList<Map<String, Object>>(); var started = Instant.now();
        try (var client = new AiConfig().aiClient(p)) {
            run(client, p, cases, results, () -> Thread.sleep(1000));
        } finally {
            var report = new LinkedHashMap<String, Object>();
            report.put("evaluation", "frozen-synthetic-classification-constraints-and-router");
            report.put("model", p.getModel()); report.put("corpusSha256", ClassifierStabilityCorpus.fingerprint());
            report.put("baselineCorpusSha256", ConversationQualityCorpus.fingerprint());
            report.put("startedAt", started.toString()); report.put("finishedAt", Instant.now().toString());
            report.put("totalOutputTokens", p.getMaxOutputTokens()); report.put("timeoutSeconds", p.getTimeoutSeconds());
            report.put("classificationMaxOutputTokens", new TurnBudget(p).classificationTokens());
            report.put("repeats", 3); report.put("plannedAttempts", cases.size() * 3); report.put("results", results);
            Files.createDirectories(Path.of("target/chat-quality"));
            Files.writeString(Path.of("target/chat-quality/stability-" + started.toString().replaceAll("[^0-9]", "") + ".json"),
                    ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        assertEquals(cases.size() * 3, results.size());
        assertTrue(results.stream().allMatch(r -> r.get("status").equals("PASS")), "Frozen stability quality gate failed; inspect synthetic report");
    }
    static void run(AiClient client, AiProperties p, List<ClassifierStabilityCorpus.Case> cases,
            List<Map<String, Object>> results, ConversationQualityLiveTest.Pause pause) throws InterruptedException {
        boolean stopped = false;
        for (int repetition = 1; repetition <= 3; repetition++) for (var test : cases) {
            var row = new LinkedHashMap<String, Object>(); results.add(row);
            row.put("id", test.id()); row.put("repetition", repetition); row.put("status", "NOT_RUN");
            var budget = new TurnBudget(p); long start = System.nanoTime();
            if (!stopped) {
                try {
                    var plan = client.classify(test.context(), budget);
                    var issues = ClassifierStabilityCorpus.issues(test, plan);
                    row.put("issues", issues); row.put("actualPlan", plan); // Synthetic fixtures ONLY.
                    row.put("status", issues.isEmpty() ? "PASS" : "MISMATCH");
                } catch (ChatException e) {
                    row.put("status", e.code()); stopped = e.status() == 429 || e.status() == 503 || e.status() == 504;
                }
            }
            row.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
            row.put("inputTokens", budget.usage().inputTokens()); row.put("outputTokens", budget.usage().outputTokens());
            if (!stopped) pause.await();
        }
    }
}
