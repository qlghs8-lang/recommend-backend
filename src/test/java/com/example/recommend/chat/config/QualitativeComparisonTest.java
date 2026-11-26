package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualitativeComparisonTest {
    private static final String QUESTION = "달빛 항해와 숲의 기록 중 더 뛰어난 연출의 작품은 뭐야?";
    private static final String OPERATION = """
            {"route":"CLARIFY","reason":"QUALITATIVE_COMPARISON","source":"더 뛰어난 연출"}
            """;
    private static List<AiMessage> context() { return List.of(new AiMessage(AiMessage.Role.USER, QUESTION)); }

    @Test void typedLimitationUsesFixedAlternativesWithoutLookupGenerationOrInventedReferences() {
        var plan = ClassifierPlan.parseProvider(DurationClarificationTest.wire(OPERATION), context());
        assertEquals(new Clarification(Clarification.Reason.QUALITATIVE_COMPARISON, "더 뛰어난 연출"), plan.clarification());
        assertEquals(plan, ChatPlan.parse(ConversationQualityCorpus.JSON.writeValueAsString(plan)));
        var client = mock(AiClient.class); var recommendations = mock(CatalogRecommendations.class);
        var interactions = mock(ChatInteractionReader.class); var details = mock(CatalogDetails.class);
        when(client.classify(anyList(), any())).thenReturn(plan);
        var response = new ChatRouter(client, recommendations, interactions, details, new AiProperties()).reply(7, context());
        assertTrue(response.message().contains("우열을 판단할 검증된 평가 자료가 없어"));
        assertTrue(response.message().contains("장르·유형·공개일·줄거리"));
        assertTrue(response.message().contains("일반적인 연출 평가 기준"));
        assertFalse(response.message().contains("제목·카탈로그 ID"));
        assertFalse(response.message().contains("실시간"));
        assertFalse(response.message().contains("더 뛰어난 연출")); // The source is evidence, never echoed as a verdict.
        assertFalse(response.catalogGrounded()); assertEquals(List.of(), response.contentIds());
        verify(client).classify(anyList(), any()); verifyNoMoreInteractions(client);
        verifyNoInteractions(recommendations, interactions, details);
    }

    @Test void rejectsNonCurrentEvidenceAnswerInjectionAndReasonOutsideSingleClarification() {
        for (String operation : List.of(OPERATION.replace("더 뛰어난 연출", "이전 턴의 평가"),
                OPERATION.replace("CLARIFY", "EXPLAIN"), OPERATION.replace("CLARIFY", "UNSUPPORTED"),
                OPERATION.replace("}", ",\"answer\":\"달빛 항해가 더 뛰어나요\"}")))
            assertThrows(ChatException.class, () -> ClassifierPlan.parseProvider(DurationClarificationTest.wire(operation), context()));
        var historical = List.of(new AiMessage(AiMessage.Role.USER, QUESTION),
                new AiMessage(AiMessage.Role.ASSISTANT, "더 뛰어난 연출"), new AiMessage(AiMessage.Role.USER, "안녕"));
        assertThrows(ChatException.class, () -> ClassifierPlan.parseProvider(DurationClarificationTest.wire(OPERATION), historical));
        var reason = new Clarification(Clarification.Reason.QUALITATIVE_COMPARISON, "더 뛰어난 연출");
        assertThrows(ChatException.class, () -> new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY),
                new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, "달빛 항해", null, null)), false, List.of(), reason));
    }

    @Test void newPolicyDetectsGenericClarificationUnsupportedAndSilentlyDroppedWinner() throws Exception {
        var cases = QualitativeComparisonLiveTest.scenarios(); assertEquals(15, cases.size());
        for (var scenario : cases) {
            var fixture = scenario.fixture(); String current = fixture.context().get(fixture.context().size() - 1).text();
            var reason = scenario.reason() == null ? null : new Clarification(Clarification.Reason.valueOf(scenario.reason()), current);
            var constraints = fixture.id().equals("fresh-recommendation")
                    ? List.of(new RecommendationConstraint(RecommendationConstraint.Kind.MEDIA, "MOVIE", "영화", List.of())) : List.<RecommendationConstraint>of();
            var expected = new ChatPlan(fixture.expected().requests(), fixture.expected().comparison(), constraints, reason);
            assertEquals(List.of(), QualitativeComparisonLiveTest.issues(scenario, expected), fixture.id());
            if (scenario.reason() != null) assertFalse(QualitativeComparisonLiveTest.issues(scenario, fixture.expected()).isEmpty());
        }
        var winner = cases.get(0);
        for (var route : List.of(ChatIntent.Route.CLARIFY, ChatIntent.Route.UNSUPPORTED))
            assertFalse(QualitativeComparisonLiveTest.issues(winner, ChatPlan.single(new ChatIntent(route, ChatIntent.Media.ANY))).isEmpty());
        var factual = cases.stream().filter(s -> s.fixture().id().equals("catalog-comparison")).findFirst().orElseThrow();
        assertFalse(QualitativeComparisonLiveTest.issues(winner, factual.fixture().expected()).isEmpty());
    }
}
