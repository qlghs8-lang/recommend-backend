package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Paid opt-in: fixed synthetic qualitative-winner policy, separate from the original baseline. */
@EnabledIfSystemProperty(named = "chatQualitativeLive", matches = "true")
class QualitativeComparisonLiveTest {
    record Scenario(ConversationQualityCorpus.Fixture fixture, String reason) { }
    static List<Scenario> scenarios() throws Exception {
        try (var stream = QualitativeComparisonLiveTest.class.getResourceAsStream("/chat/qualitative-comparison-v1.json")) {
            assertNotNull(stream);
            var root = ConversationQualityCorpus.JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            var cases = new ArrayList<Scenario>();
            for (var row : root.get("cases")) {
                var context = new ArrayList<AiMessage>();
                for (var message : row.get("history")) {
                    var ids = new ArrayList<Long>(); for (var id : message.get("contentIds")) ids.add(id.asLong());
                    context.add(new AiMessage(AiMessage.Role.valueOf(message.get("role").asString()), message.get("text").asString(), ids));
                }
                context.add(new AiMessage(AiMessage.Role.USER, row.get("question").asString()));
                cases.add(new Scenario(new ConversationQualityCorpus.Fixture(row.get("id").asString(), "qualitative-comparison", true,
                        List.copyOf(context), ChatPlan.parse(row.get("expected").toString())),
                        row.get("reason").isNull() ? null : row.get("reason").asString()));
            }
            return List.copyOf(cases);
        }
    }
    static List<String> issues(Scenario scenario, ChatPlan plan) {
        var issues = new ArrayList<String>();
        if (!ConversationQualityCorpus.evaluate(scenario.fixture(), plan).passed()) issues.add("QUALITATIVE_POLICY_OR_ROUTER");
        if (scenario.reason() == null ? plan.clarification() != null
                : plan.clarification() == null || !plan.clarification().reason().name().equals(scenario.reason())) issues.add("CLARIFICATION_REASON");
        boolean recommendation = scenario.fixture().id().equals("fresh-recommendation");
        if (recommendation ? plan.constraints().size() != 1 || plan.constraints().get(0).kind() != RecommendationConstraint.Kind.MEDIA
                || !plan.constraints().get(0).value().equals("MOVIE") : !plan.constraints().isEmpty()) issues.add("UNEXPECTED_CONSTRAINTS");
        return List.copyOf(issues);
    }

    @Test void evaluatesFixedQualitativeCasesWithoutChangingOriginalOracles() throws Exception {
        var p = new AiProperties(); p.setEnabled(true);
        p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY")); p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        var cases = scenarios(); var results = new ArrayList<Map<String, Object>>(); var started = Instant.now();
        String previous = System.getProperty("chatQualityRecordSyntheticPlans"); System.setProperty("chatQualityRecordSyntheticPlans", "true");
        try (var trace = new SyntheticClassifierTrace(p, AiConfig.createSdk(p, "https://api.openai.com/v1"))) {
            try {
                ConversationQualityLiveTest.run(trace.client, p, cases.stream().map(Scenario::fixture).toList(), 3,
                        results, () -> Thread.sleep(1000), trace.items);
                for (var row : results) if (row.get("actualPlan") instanceof ChatPlan plan) {
                    var scenario = cases.stream().filter(s -> s.fixture().id().equals(row.get("id"))).findFirst().orElseThrow();
                    var problems = issues(scenario, plan); row.put("issues", problems);
                    if (!problems.isEmpty() && row.get("status").equals("PASS")) row.put("status", "MISMATCH");
                }
            } finally {
                Files.createDirectories(Path.of("target/chat-quality"));
                Files.writeString(Path.of("target/chat-quality/qualitative-comparison-" + started.toString().replaceAll("[^0-9]", "") + ".json"),
                        ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                                "evaluation", "separate-synthetic-qualitative-comparison-policy-not-v1-baseline", "model", p.getModel(),
                                "totalOutputTokens", p.getMaxOutputTokens(), "classificationMaxOutputTokens", new TurnBudget(p).classificationTokens(),
                                "repeats", 3, "cases", cases, "results", results)));
            }
        } finally {
            if (previous == null) System.clearProperty("chatQualityRecordSyntheticPlans");
            else System.setProperty("chatQualityRecordSyntheticPlans", previous);
        }
        assertEquals(cases.size() * 3, results.size());
        assertTrue(results.stream().allMatch(row -> row.get("status").equals("PASS")), "Separate qualitative-comparison policy failed; original score is preserved");
    }
}
