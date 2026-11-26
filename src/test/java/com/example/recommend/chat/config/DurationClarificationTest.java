package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DurationClarificationTest {
    static final String QUESTION = "짧게 볼 만한 시리즈 있을까?";
    static String wire(String operation) {
        return "{\"composition\":{\"requests\":[" + operation + "],\"comparison\":false,\"splitAfter\":null},\"constraints\":[]}";
    }
    static String operation(String source) {
        return "{\"route\":\"CLARIFY\",\"reason\":\"TV_DURATION_UNIT\",\"source\":\"" + source + "\"}";
    }
    private static List<AiMessage> context() { return List.of(new AiMessage(AiMessage.Role.USER, QUESTION)); }

    @Test void separatePolicyOraclesCheckReasonsAndDoNotAcceptConditionLoss() throws Exception {
        var scenarios = DurationClarificationLiveTest.scenarios();
        assertEquals(12, scenarios.size());
        for (var scenario : scenarios) {
            String question = scenario.fixture().context().get(scenario.fixture().context().size() - 1).text();
            var constraints = scenario.constraints().stream().map(expected -> new RecommendationConstraint(
                    RecommendationConstraint.Kind.valueOf(expected.kind()), expected.value().equals("*") ? "series total" : expected.value(),
                    question, List.<String>of())).toList();
            var clarification = scenario.reason() == null ? null
                    : new Clarification(Clarification.Reason.valueOf(scenario.reason()), question);
            var expected = new ChatPlan(scenario.fixture().expected().requests(), false, constraints, clarification);
            assertEquals(List.of(), DurationClarificationLiveTest.issues(scenario, expected), scenario.fixture().id());
            if (clarification != null) assertTrue(DurationClarificationLiveTest.issues(scenario,
                    scenario.fixture().expected()).contains("CLARIFICATION_REASON"));
            if (!constraints.isEmpty()) assertFalse(DurationClarificationLiveTest.issues(scenario,
                    scenario.fixture().expected()).isEmpty());
        }
    }

    @Test void parsesReasonAndRendersOnlyTheServerQuestionWithoutStartingOtherWork() {
        var plan = ClassifierPlan.parseProvider(wire(operation("짧게 볼 만한")), context());
        assertEquals(new Clarification(Clarification.Reason.TV_DURATION_UNIT, "짧게 볼 만한"), plan.clarification());
        assertEquals(plan, ChatPlan.parse(ConversationQualityCorpus.JSON.writeValueAsString(plan)));
        var client = mock(AiClient.class); var recommendations = mock(CatalogRecommendations.class);
        var interactions = mock(ChatInteractionReader.class); var details = mock(CatalogDetails.class);
        when(client.classify(anyList(), any())).thenReturn(plan);
        var result = new ChatRouter(client, recommendations, interactions, details, new AiProperties()).reply(7, context());
        assertTrue(result.message().contains("회당 길이가 짧은 시리즈"));
        assertTrue(result.message().contains("전체 시청 시간은 확인할 수 없어요"));
        assertFalse(result.message().contains("제목·카탈로그 ID"));
        assertFalse(result.catalogGrounded()); assertEquals(List.of(), result.contentIds());
        verify(client).classify(anyList(), any()); verifyNoMoreInteractions(client);
        verifyNoInteractions(recommendations, interactions, details);
    }

    @Test void rejectsUnknownMalformedOrNonCurrentEvidenceWithoutRepairingValues() {
        for (String op : List.of(operation("다른 턴의 짧은 시리즈"), operation(""), operation(" 짧게"),
                operation("짧게").replace("TV_DURATION_UNIT", "ARBITRARY"),
                operation("짧게").replace("\"source\":\"짧게\"", "\"source\":null"),
                operation("짧게").replace("CLARIFY", "EXPLAIN"),
                "{\"route\":\"CLARIFY\",\"reason\":\"TV_DURATION_UNIT\"}",
                "{\"route\":\"CLARIFY\",\"source\":\"짧게\"}",
                operation("짧게").replace("}", ",\"answer\":\"모델 문구\"}")))
            assertEquals("AI_INVALID_RESPONSE", assertThrows(ChatException.class,
                    () -> ClassifierPlan.parseProvider(wire(op), context())).code(), op);
        var historicalOnly = List.of(new AiMessage(AiMessage.Role.USER, QUESTION),
                new AiMessage(AiMessage.Role.ASSISTANT, "짧게 볼 만한"), new AiMessage(AiMessage.Role.USER, "작품 설명해줘"));
        assertThrows(ChatException.class, () -> ClassifierPlan.parseProvider(wire(operation("짧게 볼 만한")), historicalOnly));
        assertThrows(ChatException.class, () -> ClassifierPlan.parseProvider(wire(operation("짧게")) + "{}", context()));
    }

    @Test void reasonCannotBeAttachedToAnotherRouteOrCompoundAndRouterRechecksSource() {
        var reason = new Clarification(Clarification.Reason.TV_DURATION_UNIT, "짧게");
        assertThrows(ChatException.class, () -> new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.EXPLAIN, ChatIntent.Media.ANY)), false, List.of(), reason));
        assertThrows(ChatException.class, () -> new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY),
                new ChatIntent(ChatIntent.Route.EXPLAIN, ChatIntent.Media.ANY)), false, List.of(), reason));
        var client = mock(AiClient.class); var recommendations = mock(CatalogRecommendations.class);
        var details = mock(CatalogDetails.class); var interactions = mock(ChatInteractionReader.class);
        when(client.classify(anyList(), any())).thenReturn(new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY)),
                false, List.of(), new Clarification(Clarification.Reason.TV_DURATION_UNIT, "원문에 없음")));
        assertThrows(ChatException.class, () -> new ChatRouter(client, recommendations, interactions, details, new AiProperties()).reply(7, context()));
        verifyNoInteractions(recommendations, details, interactions);
    }

    @Test void schemaKeepsRouteFirstAndAllowsReasonOnlyForSingleClarification() {
        var schema = ConversationQualityCorpus.JSON.readTree(ConversationQualityCorpus.JSON.writeValueAsString(ClassifierPlan.compositionSchema(QUESTION)));
        var single = schema.get("anyOf").get(0).get("properties").get("requests").get("items").get("anyOf");
        var reasoned = single.get(5);
        assertEquals(List.of("route", "reason", "source"), reasoned.get("properties").propertyNames().stream().toList());
        assertEquals(3, reasoned.get("required").size()); assertFalse(reasoned.get("additionalProperties").asBoolean());
        assertEquals("CLARIFY", reasoned.get("properties").get("route").get("enum").get(0).asString());
        var independent = schema.get("anyOf").get(2).get("properties").get("requests").get("items").get("anyOf");
        assertEquals(5, independent.size());
        for (var branch : independent) assertFalse(branch.get("properties").has("reason"));
        var ordinary = ClassifierPlan.parseProvider(wire("{\"route\":\"CLARIFY\"}"), context());
        assertNull(ordinary.clarification());
        assertTrue(ConversationQualityCorpus.evaluate(new ConversationQualityCorpus.Fixture("ordinary", "clarification", false, context(), ordinary), ordinary).passed());
    }
}
