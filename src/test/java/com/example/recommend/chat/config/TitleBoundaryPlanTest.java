package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TitleBoundaryPlanTest {
    @Test void typedReasonRequiresCurrentEvidenceAndRendersOnlyTheServerQuestion() {
        String question = "없는 작품 XYZ의 줄거리 알려줘";
        String operation = "{\"route\":\"CLARIFY\",\"reason\":\"TITLE_BOUNDARY\",\"source\":\"없는 작품 XYZ\"}";
        var context = List.of(new AiMessage(AiMessage.Role.USER, question));
        var plan = ClassifierPlan.parseProvider(DurationClarificationTest.wire(operation), context);
        assertEquals(Clarification.Reason.TITLE_BOUNDARY, plan.clarification().reason());
        assertEquals(plan, ChatPlan.parse(ConversationQualityCorpus.JSON.writeValueAsString(plan)));
        var client = mock(AiClient.class); var recommendations = mock(CatalogRecommendations.class);
        var interactions = mock(ChatInteractionReader.class); var details = mock(CatalogDetails.class);
        when(client.classify(anyList(), any())).thenReturn(plan);
        var response = new ChatRouter(client, recommendations, interactions, details, new AiProperties()).reply(7, context);
        assertTrue(response.message().contains("정확한 제목 전체를 따옴표로"));
        assertFalse(response.catalogGrounded()); assertEquals(List.of(), response.contentIds());
        verifyNoInteractions(recommendations, interactions, details); verify(client).classify(anyList(), any()); verifyNoMoreInteractions(client);
        assertThrows(ChatException.class, () -> ClassifierPlan.parseProvider(DurationClarificationTest.wire(operation),
                List.of(new AiMessage(AiMessage.Role.USER, question), new AiMessage(AiMessage.Role.ASSISTANT, "없는 작품 XYZ"),
                        new AiMessage(AiMessage.Role.USER, "그 작품 줄거리"))));
        assertThrows(ChatException.class, () -> ClassifierPlan.parseProvider(DurationClarificationTest.wire(operation.replace("CLARIFY", "DETAIL")), context));
    }

    @Test void separateOraclesDetectCroppedTitlesAndMissingSpecificReasons() throws Exception {
        var cases = TitleBoundaryLiveTest.scenarios(); assertEquals(13, cases.size());
        for (var scenario : cases) {
            var fixture = scenario.fixture(); var context = fixture.context();
            var reason = scenario.reason() == null ? null : new Clarification(Clarification.Reason.valueOf(scenario.reason()), context.get(context.size() - 1).text());
            var expected = new ChatPlan(fixture.expected().requests(), fixture.expected().comparison(), List.of(), reason);
            assertEquals(List.of(), TitleBoundaryLiveTest.issues(scenario, expected), fixture.id());
            if (reason != null) assertTrue(TitleBoundaryLiveTest.issues(scenario, fixture.expected()).contains("CLARIFICATION_REASON"));
            if (fixture.id().equals("quoted-full-single")) assertTrue(TitleBoundaryLiveTest.issues(scenario,
                    ChatPlan.single(new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, "XYZ", null, null))).contains("TITLE_LOCATOR_OR_ROUTER"));
        }
    }

    @Test void singleClarificationSchemaRetainsOnlyAllowlistedReasonsAndRouteFirst() {
        var schema = ConversationQualityCorpus.JSON.readTree(ConversationQualityCorpus.JSON.writeValueAsString(ClassifierPlan.operationSchema()));
        var variant = schema.get("anyOf").get(5);
        assertEquals(List.of("route", "reason", "source"), variant.get("properties").propertyNames().stream().toList());
        assertEquals(List.of("TV_DURATION_UNIT", "TITLE_BOUNDARY", "QUALITATIVE_COMPARISON"), variant.get("properties").get("reason").get("enum").valueStream().filter(n -> !n.isNull()).map(n -> n.asString()).toList());
        assertTrue(variant.get("properties").get("reason").get("enum").valueStream().anyMatch(n -> n.isNull()));
        assertFalse(variant.get("additionalProperties").asBoolean()); assertEquals(3, variant.get("required").size());
    }

    @Test void quotedQuestionsUseSafeVerbatimBoundarySuffixesWithoutLosingSourceText() {
        String question = "\"없는 작품 XYZ\"의 줄거리와 내 찜 목록 보여줘";
        var schema = ConversationQualityCorpus.JSON.readTree(ConversationQualityCorpus.JSON.writeValueAsString(ClassifierPlan.compositionSchema(question)));
        var options = schema.get("anyOf").get(2).get("properties").get("splitAfter").get("enum");
        assertTrue(options.valueStream().allMatch(n -> !n.asString().contains("\"")));
        assertTrue(options.valueStream().anyMatch(n -> n.asString().equals("의")));
        String wire = """
                {"composition":{"requests":[{"route":"DETAIL","media":"ANY","title":"없는 작품 XYZ","contentId":null,"releaseYear":null,"referenceTurn":null,"referencePosition":null},{"route":"LIST_BOOKMARK","media":"ANY"}],"comparison":false,"splitAfter":"줄거리와"},"constraints":[]}
                """;
        var plan = ClassifierPlan.parseProvider(wire, List.of(new AiMessage(AiMessage.Role.USER, question)));
        assertEquals(question, plan.requests().get(0).question() + plan.requests().get(1).question());
        assertEquals("\"없는 작품 XYZ\"의 줄거리와", plan.requests().get(0).question());
        assertEquals("없는 작품 XYZ", plan.requests().get(0).title());
        assertThrows(ChatException.class, () -> ClassifierPlan.parseProvider(wire.replace("줄거리와\"}", "XYZ\\\"의\"}"),
                List.of(new AiMessage(AiMessage.Role.USER, question))));
    }
}
