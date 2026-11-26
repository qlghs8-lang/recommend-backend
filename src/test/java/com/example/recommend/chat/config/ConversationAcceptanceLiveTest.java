package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exactly the original 61 synthetic questions once, scored against two separately versioned policies. */
@EnabledIfSystemProperty(named = "chatAcceptanceLive", matches = "true")
class ConversationAcceptanceLiveTest {
    @Test void reportsFrozenV1AndRevisedPolicySideBySideWithoutRewritingEitherOracle() throws Exception {
        var p = new AiProperties(); p.setEnabled(true);
        p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY")); p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        var cases = ConversationQualityCorpus.load(); var results = new ArrayList<Map<String, Object>>(); var started = Instant.now();
        assertEquals(ConversationAcceptanceCorpus.ORIGINAL_HASH, ConversationQualityCorpus.fingerprint());
        String previous = System.getProperty("chatQualityRecordSyntheticPlans"); System.setProperty("chatQualityRecordSyntheticPlans", "true");
        try (var trace = new SyntheticClassifierTrace(p, AiConfig.createSdk(p, "https://api.openai.com/v1"))) {
            try {
                ConversationQualityLiveTest.run(trace.client, p, cases, 1, results, () -> Thread.sleep(1000), trace.items);
                for (var row : results) {
                    row.put("frozenV1Status", row.get("status"));
                    if (row.get("actualPlan") instanceof ChatPlan plan) {
                        var fixture = cases.stream().filter(f -> f.id().equals(row.get("id"))).findFirst().orElseThrow();
                        var issues = ConversationAcceptanceCorpus.revisedIssues(fixture, plan);
                        row.put("revisedIssues", issues); row.put("revisedStatus", issues.isEmpty() ? "PASS" : "MISMATCH");
                    } else row.put("revisedStatus", row.get("status"));
                }
            } finally {
                Files.createDirectories(Path.of("target/chat-quality"));
                Files.writeString(Path.of("target/chat-quality/acceptance-baseline-" + started.toString().replaceAll("[^0-9]", "") + ".json"),
                        ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                                "evaluation", "classification-and-mocked-router-dual-policy-not-service-acceptance", "model", p.getModel(),
                                "originalSha256", ConversationQualityCorpus.fingerprint(), "policySha256", ConversationAcceptanceCorpus.fingerprint(),
                                "totalOutputTokens", p.getMaxOutputTokens(), "classificationMaxOutputTokens", new TurnBudget(p).classificationTokens(),
                                "plannedAttempts", 61, "results", results)));
            }
        } finally {
            if (previous == null) System.clearProperty("chatQualityRecordSyntheticPlans"); else System.setProperty("chatQualityRecordSyntheticPlans", previous);
        }
        assertEquals(61, results.size());
        assertTrue(results.stream().allMatch(r -> "PASS".equals(r.get("revisedStatus"))), "Revised policy failed; preserve both scores and inspect the synthetic report");
    }
}
