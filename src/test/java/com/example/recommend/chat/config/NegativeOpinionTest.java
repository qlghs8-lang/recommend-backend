package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NegativeOpinionTest {
    static List<NegativeOpinionLiveTest.Scenario> scenarios() throws Exception { return NegativeOpinionLiveTest.scenarios(); }

    @ParameterizedTest(name = "{0}") @MethodSource("scenarios")
    void fixedOraclesExecuteAndCheckAllExpectedConditions(NegativeOpinionLiveTest.Scenario scenario) {
        var fixture = scenario.fixture();
        String question = fixture.context().get(0).text();
        var conditions = scenario.constraints().stream().map(c -> new RecommendationConstraint(
                RecommendationConstraint.Kind.valueOf(c.kind()), c.value(),
                c.kind().equals("MEDIA") ? question.contains("시리즈") ? "시리즈" : question.contains("movie") ? "movie" : "영화"
                        : question.contains("코미디") ? "코미디" : question.contains("science fiction") ? "science fiction" : "SF", List.of())).toList();
        var plan = new ChatPlan(fixture.expected().requests(), false, conditions);
        assertEquals(List.of(), NegativeOpinionLiveTest.issues(scenario, plan));
        var wrongRoute = ChatPlan.single(new ChatIntent(ChatIntent.Route.GREETING, ChatIntent.Media.ANY));
        assertFalse(NegativeOpinionLiveTest.issues(scenario, wrongRoute).isEmpty());
        if (!conditions.isEmpty()) {
            assertFalse(NegativeOpinionLiveTest.issues(scenario, new ChatPlan(plan.requests(), false, List.of())).isEmpty());
            var invented = conditions.stream().map(c -> new RecommendationConstraint(c.kind(), c.value(), "존재하지않는원문", List.of())).toList();
            assertFalse(NegativeOpinionLiveTest.issues(scenario, new ChatPlan(plan.requests(), false, invented)).isEmpty());
        }
    }

    @Test void keepsOriginalFailedFixtureAndFrozenScope() throws Exception {
        var cases = scenarios();
        assertEquals(8, cases.size()); assertEquals(8, cases.stream().map(s -> s.fixture().id()).distinct().count());
        assertEquals(ConversationQualityCorpus.load().stream().filter(f -> f.id().equals("rec-negative-opinion")).findFirst().orElseThrow(),
                cases.get(0).fixture());
        assertEquals(544, new TurnBudget(new AiProperties()).classificationTokens());
    }

    @Test void observedCompletedClarificationStillFailsTheRecommendationOracleDespiteValidJson() throws Exception {
        try (var stream = getClass().getResourceAsStream("/chat/negative-opinion-provider-v1.json")) {
            assertNotNull(stream);
            var sample = ConversationQualityCorpus.JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            var scenario = scenarios().stream().filter(s -> s.fixture().id().equals(sample.get("id").asString())).findFirst().orElseThrow();
            var trace = sample.get("providerTrace").get(0);
            assertEquals("completed", trace.get("status").asString());
            assertTrue(trace.get("outputTokens").asInt() < sample.get("classificationMaxOutputTokens").asInt());
            // Replay the historical provider layout through the flat adapter, retaining every observed value.
            var historical = ConversationQualityCorpus.JSON.readTree(trace.get("syntheticOutput").asString());
            var flat = ConversationQualityCorpus.JSON.createObjectNode();
            flat.set("requests", historical.get("requests"));
            flat.set("comparison", historical.get("composition").get("comparison"));
            flat.set("splitAfter", historical.get("composition").get("splitAfter"));
            flat.set("constraints", historical.get("constraints"));
            var plan = ClassifierPlan.parse(flat.toString(), scenario.fixture().context());
            assertEquals(ChatIntent.Route.CLARIFY, plan.requests().get(0).route());
            var verdict = ConversationQualityCorpus.evaluate(scenario.fixture(), plan);
            assertTrue(verdict.executable());
            assertFalse(verdict.semantic());
            assertFalse(NegativeOpinionLiveTest.issues(scenario, plan).isEmpty());
        }
    }
}
