package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.ChatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationQualityTest {
    static List<ConversationQualityCorpus.Fixture> fixtures() throws Exception { return ConversationQualityCorpus.load(); }

    @ParameterizedTest(name = "{0}") @MethodSource("fixtures")
    void oraclePlanIsExecutableThroughProductionRouter(ConversationQualityCorpus.Fixture fixture) {
        var result = ConversationQualityCorpus.evaluate(fixture, fixture.expected());
        assertTrue(result.passed(), fixture.id());
    }

    @Test void baselineCorpusHasUniqueIdsParaphrasesAndItsOriginalCapabilities() throws Exception {
        var fixtures = fixtures();
        assertTrue(fixtures.size() >= 60);
        assertEquals(fixtures.size(), fixtures.stream().map(ConversationQualityCorpus.Fixture::id).distinct().count());
        assertEquals(fixtures.size(), fixtures.stream().map(f -> f.context().get(f.context().size() - 1).text()).distinct().count());
        var routes = EnumSet.noneOf(ChatIntent.Route.class);
        fixtures.forEach(f -> f.expected().requests().forEach(i -> routes.add(i.route())));
        var baselineRoutes = EnumSet.allOf(ChatIntent.Route.class);
        // Keep the measured 61-case v1 baseline immutable; the dedicated rationale suite covers its new route.
        baselineRoutes.remove(ChatIntent.Route.RATIONALE);
        assertEquals(baselineRoutes, routes);
        for (String category : List.of("recommendation", "personalization", "reaction-list", "detail", "explanation",
                "reference", "compound", "comparison", "privacy", "unsupported", "clarification", "scope"))
            assertTrue(fixtures.stream().filter(f -> f.category().equals(category)).count() >= 3, category);
        assertTrue(fixtures.stream().filter(ConversationQualityCorpus.Fixture::critical).count() >= 15);
        assertEquals(64, ConversationQualityCorpus.fingerprint().length());
        assertEquals(12, fixtures.stream().limit(12).map(ConversationQualityCorpus.Fixture::category).distinct().count());
    }

    @Test void scorerRejectsSemanticAndSpanErrorsAndAcceptsEquivalentWhitespace() throws Exception {
        var fixtures = fixtures();
        var list = fixtures.stream().filter(f -> f.id().equals("list-dislike-movie")).findFirst().orElseThrow();
        var wrong = ChatPlan.single(new ChatIntent(ChatIntent.Route.PERSONALIZED, ChatIntent.Media.ANY));
        var wrongResult = ConversationQualityCorpus.evaluate(list, wrong);
        assertFalse(wrongResult.semantic());
        assertTrue(wrongResult.executable()); // Valid JSON/capability alone does not imply correct intent.
        var compound = fixtures.stream().filter(f -> f.id().equals("compound-list-rec")).findFirst().orElseThrow();
        var first = compound.expected().requests().get(0);
        var second = compound.expected().requests().get(1);
        var dropped = new ChatPlan(List.of(first, new ChatIntent(second.route(), second.media(), null, null, null,
                null, null, "추천해줘")), false);
        assertFalse(ConversationQualityCorpus.evaluate(compound, dropped).semantic());
        assertFalse(ConversationQualityCorpus.evaluate(compound, dropped).executable());
        var alternative = new ChatPlan(List.of(new ChatIntent(first.route(), first.media(), null, null, null,
                null, null, "내 찜 목록 보여주고 "), new ChatIntent(second.route(), second.media(), null, null, null,
                null, null, "SF 영화 추천해줘")), false);
        assertTrue(ConversationQualityCorpus.evaluate(compound, alternative).passed());
        assertFalse(ConversationQualityCorpus.evaluate(list, null).passed());
    }

    @Test void runnerCountsUsageAndDoesNotScoreErrorsOrUnattemptedCasesAsSuccess() throws Exception {
        var fixture = fixtures().stream().filter(f -> f.id().equals("privacy-other")).findFirst().orElseThrow();
        var client = mock(AiClient.class);
        when(client.classify(anyList(), any())).thenAnswer(call -> {
            ((TurnBudget) call.getArgument(1)).consume(new ChatResponse.Usage(10, 20, 30));
            return fixture.expected();
        }).thenThrow(ChatException.invalidResponse()).thenThrow(ChatException.busy());
        var rows = new ArrayList<Map<String, Object>>();
        ConversationQualityLiveTest.run(client, new AiProperties(), List.of(fixture), 5, rows, () -> {});
        assertEquals(List.of("PASS", "AI_INVALID_RESPONSE", "AI_BUSY", "NOT_RUN", "NOT_RUN"),
                rows.stream().map(r -> r.get("status")).toList());
        verify(client, times(3)).classify(anyList(), any());
        var summary = (Map<?, ?>) ConversationQualityLiveTest.summaries(rows).get("privacy");
        assertEquals(1L, summary.get("passed"));
        assertEquals(4L, summary.get("criticalFailed"));
        assertEquals(10L, summary.get("inputTokens"));
        assertEquals(20L, summary.get("outputTokens"));
        String report = ConversationQualityCorpus.JSON.writeValueAsString(rows);
        assertFalse(report.contains(fixture.context().get(0).text()));
        assertFalse(report.contains("requests"));
    }

    @Test void referenceAndLocatorMistakesAreNotHiddenBySchemaValidity() throws Exception {
        var fixtures = fixtures();
        var ref = fixtures.stream().filter(f -> f.id().equals("ref-second")).findFirst().orElseThrow();
        var singular = ChatPlan.single(new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY,
                null, null, null, 1, 0, null));
        assertFalse(ConversationQualityCorpus.evaluate(ref, singular).passed());
        var detail = fixtures.stream().filter(f -> f.id().equals("detail-year")).findFirst().orElseThrow();
        var missingYear = ChatPlan.single(new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.MOVIE, "달빛 항해", null, null));
        assertFalse(ConversationQualityCorpus.evaluate(detail, missingYear).semantic());
        var singleRef = fixtures.stream().filter(f -> f.id().equals("ref-single")).findFirst().orElseThrow();
        var positionOne = ChatPlan.single(new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY,
                null, null, null, 1, 1, null));
        assertTrue(ConversationQualityCorpus.evaluate(singleRef, positionOne).passed());
        var whyInsteadOfDetail = ChatPlan.single(new ChatIntent(ChatIntent.Route.RATIONALE, ChatIntent.Media.ANY,
                null, null, null, 1, 0, null));
        var scored = ConversationQualityCorpus.evaluate(singleRef, whyInsteadOfDetail);
        assertFalse(scored.semantic());
        assertTrue(scored.executable());
    }

    @Test void optInTraceStaysWithItsAttemptIncludingInvalidOutputPreProviderFailureAndCircuitOpen() throws Exception {
        var fixture = fixtures().stream().filter(f -> f.id().equals("explain-format")).findFirst().orElseThrow();
        var client = mock(AiClient.class); var trace = new ArrayList<Map<String, Object>>();
        when(client.classify(anyList(), any())).thenAnswer(call -> {
            trace.add(Map.of("syntheticOutput", "fixed-invalid-json")); throw ChatException.invalidResponse();
        }).thenReturn(fixture.expected()).thenAnswer(call -> {
            trace.add(Map.of("syntheticOutput", "fixed-success")); return fixture.expected();
        }).thenThrow(ChatException.busy());
        var rows = new ArrayList<Map<String, Object>>();
        ConversationQualityLiveTest.run(client, new AiProperties(), List.of(fixture), 5, rows, () -> {}, trace);
        assertEquals(List.of("AI_INVALID_RESPONSE", "PASS", "PASS", "AI_BUSY", "NOT_RUN"), rows.stream().map(r -> r.get("status")).toList());
        assertEquals(List.of(trace.get(0)), rows.get(0).get("providerTrace"));
        assertEquals(List.of(), rows.get(1).get("providerTrace"));
        assertEquals(List.of(trace.get(1)), rows.get(2).get("providerTrace"));
        assertEquals(List.of(), rows.get(4).get("providerTrace"));
        verify(client, times(4)).classify(anyList(), any());
    }
}
