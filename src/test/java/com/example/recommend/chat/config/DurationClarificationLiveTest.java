package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.RecommendationCriteria;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed synthetic policy scope, explicitly opted in; never a replacement for the v1 baseline. */
@EnabledIfSystemProperty(named = "chatDurationLive", matches = "true")
class DurationClarificationLiveTest {
    record Scenario(ConversationQualityCorpus.Fixture fixture, String reason,
            List<ClassifierStabilityCorpus.Expected> constraints) { }

    static List<Scenario> scenarios() throws Exception {
        try (var stream = DurationClarificationLiveTest.class.getResourceAsStream("/chat/duration-clarification-v1.json")) {
            assertNotNull(stream);
            var rows = ConversationQualityCorpus.JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            var cases = new ArrayList<Scenario>();
            for (var row : rows.get("cases")) {
                var expected = new ArrayList<ClassifierStabilityCorpus.Expected>();
                for (var constraint : row.get("constraints")) expected.add(new ClassifierStabilityCorpus.Expected(
                        constraint.get("kind").asString(), constraint.get("value").isNull() ? "*" : constraint.get("value").asString()));
                var intent = new ChatIntent(ChatIntent.Route.valueOf(row.get("route").asString()), ChatIntent.Media.ANY);
                var context = new ArrayList<AiMessage>();
                if (row.has("history")) for (var message : row.get("history")) context.add(new AiMessage(
                        AiMessage.Role.valueOf(message.get("role").asString()), message.get("text").asString()));
                context.add(new AiMessage(AiMessage.Role.USER, row.get("question").asString()));
                var fixture = new ConversationQualityCorpus.Fixture(row.get("id").asString(), "duration-policy", true,
                        List.copyOf(context), ChatPlan.single(intent));
                cases.add(new Scenario(fixture, row.get("reason").isNull() ? null : row.get("reason").asString(), List.copyOf(expected)));
            }
            return List.copyOf(cases);
        }
    }

    static List<String> issues(Scenario scenario, ChatPlan plan) {
        var fixture = scenario.fixture();
        var issues = new ArrayList<String>();
        if (!ConversationQualityCorpus.evaluate(fixture, plan).passed()) issues.add("INTENT_OR_ROUTER");
        if (scenario.reason() == null ? plan.clarification() != null
                : plan.clarification() == null || !plan.clarification().reason().name().equals(scenario.reason())) issues.add("CLARIFICATION_REASON");
        if (plan.constraints().size() != scenario.constraints().size()) issues.add("EXTRA_OR_MISSING_CONSTRAINT");
        for (var expected : scenario.constraints()) if (plan.constraints().stream().filter(c -> c.kind().name().equals(expected.kind())
                && (expected.value().equals("*") || c.value().equals(expected.value()))).count() != 1) issues.add("CONSTRAINT_" + expected.kind());
        try {
            var criteria = RecommendationCriteria.from(plan.constraints(), fixture.context(), fixture.context().get(fixture.context().size() - 1).text());
            // Unlike the stability corpus, these controls REQUIRE the existing unverifiable-condition gate.
            boolean unverifiable = scenario.constraints().stream().anyMatch(c -> c.kind().equals("UNVERIFIABLE"));
            if ((criteria.clarification() != null) != unverifiable) issues.add("CRITERIA_POLICY");
        } catch (RuntimeException e) { issues.add("INVALID_CONSTRAINT_SOURCE"); }
        return List.copyOf(issues);
    }

    @Test void evaluatesTheSeparateDurationPolicyAtUnchangedProductionLimits() throws Exception {
        var p = new AiProperties(); p.setEnabled(true);
        p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY")); p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        var cases = scenarios(); var started = Instant.now(); var results = new ArrayList<Map<String, Object>>();
        String previous = System.getProperty("chatQualityRecordSyntheticPlans");
        System.setProperty("chatQualityRecordSyntheticPlans", "true");
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
                Files.writeString(Path.of("target/chat-quality/duration-clarification-" + started.toString().replaceAll("[^0-9]", "") + ".json"),
                        ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                                "evaluation", "separate-synthetic-TV-duration-policy-not-v1-baseline", "model", p.getModel(),
                                "totalOutputTokens", p.getMaxOutputTokens(), "classificationMaxOutputTokens", new TurnBudget(p).classificationTokens(),
                                "repeats", 3, "cases", cases, "results", results)));
            }
        } finally {
            if (previous == null) System.clearProperty("chatQualityRecordSyntheticPlans");
            else System.setProperty("chatQualityRecordSyntheticPlans", previous);
        }
        assertEquals(cases.size() * 3, results.size());
        assertTrue(results.stream().allMatch(row -> row.get("status").equals("PASS")), "Separate duration-policy scope failed; original score remains unchanged");
    }
}
