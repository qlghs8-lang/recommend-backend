package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClarificationReasonTest {
    private static final List<AiMessage> CONTEXT = List.of(new AiMessage(AiMessage.Role.USER, "없는 작품 XYZ의 줄거리 알려줘"));

    @Test void everyProviderClarificationBranchRequiresAnExplicitReasonAndSourceDecision() {
        var schema = ConversationQualityCorpus.JSON.readTree(ConversationQualityCorpus.JSON.writeValueAsString(ClassifierPlan.operationSchema()));
        int branches = 0;
        for (var variant : schema.get("anyOf")) {
            var properties = variant.get("properties");
            if (properties.get("route").get("enum").valueStream().noneMatch(n -> n.asString().equals("CLARIFY"))) continue;
            branches++;
            assertEquals(List.of("route", "reason", "source"), properties.propertyNames().stream().toList());
            assertEquals(List.of("route", "reason", "source"), variant.get("required").valueStream().map(n -> n.asString()).toList());
            assertFalse(variant.get("additionalProperties").asBoolean());
            assertEquals(1, properties.get("route").get("enum").size());
            assertEquals(List.of("string", "null"), properties.get("reason").get("type").valueStream().map(n -> n.asString()).toList());
            assertEquals(4, properties.get("reason").get("enum").size());
            assertTrue(properties.get("reason").get("enum").valueStream().anyMatch(n -> n.isNull()));
        }
        assertEquals(1, branches); // The recorded route-only completion has no matching provider schema branch.
    }

    @Test void explicitGeneralClarificationPreservesLegacyReplyAndMakesNoLookupOrGeneration() {
        var legacy = ClassifierPlan.parseProvider(DurationClarificationTest.wire("{\"route\":\"CLARIFY\"}"), CONTEXT);
        var explicit = ClassifierPlan.parseProvider(DurationClarificationTest.wire("{\"route\":\"CLARIFY\",\"reason\":null,\"source\":null}"), CONTEXT);
        assertEquals(legacy, explicit); assertNull(explicit.clarification());
        var client = mock(AiClient.class); var recs = mock(CatalogRecommendations.class);
        var details = mock(CatalogDetails.class); var lists = mock(ChatInteractionReader.class);
        when(client.classify(anyList(), any())).thenReturn(explicit);
        var response = new ChatRouter(client, recs, lists, details, new AiProperties()).reply(7, CONTEXT);
        assertTrue(response.message().contains("제목·카탈로그 ID"));
        assertFalse(response.catalogGrounded()); assertTrue(response.contentIds().isEmpty());
        verify(client).classify(anyList(), any()); verifyNoMoreInteractions(client); verifyNoInteractions(recs, details, lists);
    }

    @Test void mixedNullsMalformedValuesAndExtraFieldsCannotBecomeGenericClarification() {
        for (String operation : List.of(
                "{\"route\":\"CLARIFY\",\"reason\":null,\"source\":\"없는 작품 XYZ\"}",
                "{\"route\":\"CLARIFY\",\"reason\":\"TITLE_BOUNDARY\",\"source\":null}",
                "{\"route\":\"CLARIFY\",\"reason\":null}",
                "{\"route\":\"CLARIFY\",\"source\":null}",
                "{\"route\":\"CLARIFY\",\"reason\":42,\"source\":true}",
                "{\"route\":\"CLARIFY\",\"reason\":\"UNKNOWN\",\"source\":\"없는 작품 XYZ\"}",
                "{\"route\":\"CLARIFY\",\"reason\":\"TITLE_BOUNDARY\",\"source\":\"현재 원문에 없음\"}",
                "{\"route\":\"CLARIFY\",\"reason\":null,\"source\":null,\"answer\":\"모델 문구\"}",
                "{\"route\":\"EXPLAIN\",\"reason\":null,\"source\":null}"))
            assertEquals("AI_INVALID_RESPONSE", assertThrows(ChatException.class,
                    () -> ClassifierPlan.parseProvider(DurationClarificationTest.wire(operation), CONTEXT)).code());
        String compound = """
                {"composition":{"requests":[{"route":"CLARIFY","reason":null,"source":null},{"route":"LIST_BOOKMARK","media":"ANY"}],"comparison":false,"splitAfter":"XYZ의"},"constraints":[]}
                """;
        assertThrows(ChatException.class, () -> ClassifierPlan.parseProvider(compound, CONTEXT));
    }

    @Test void fixedOraclesRequireSpecificReasonsAndKeepGenericClarificationDistinct() throws Exception {
        var cases = ClarificationReasonLiveTest.scenarios(); assertEquals(9, cases.size());
        for (var scenario : cases) {
            var fixture = scenario.fixture(); var current = fixture.context().get(fixture.context().size() - 1).text();
            var reason = scenario.reason() == null ? null : new Clarification(Clarification.Reason.valueOf(scenario.reason()), current);
            var expected = new ChatPlan(fixture.expected().requests(), fixture.expected().comparison(), List.of(), reason);
            assertEquals(List.of(), ClarificationReasonLiveTest.issues(scenario, expected), fixture.id());
            if (reason != null) assertTrue(ClarificationReasonLiveTest.issues(scenario, fixture.expected()).contains("CLARIFICATION_REASON"));
        }
    }
}
