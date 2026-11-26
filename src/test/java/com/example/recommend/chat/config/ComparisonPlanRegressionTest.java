package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.ChatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Historical successes plus the newly observed invalid boundary; original oracles remain unchanged. */
class ComparisonPlanRegressionTest {
    record Replay(ConversationQualityCorpus.Fixture fixture, int repetition, String output) {
        @Override public String toString() { return fixture.id() + "-" + repetition; }
    }

    static List<Replay> replays() throws Exception {
        var fixtures = ConversationQualityCorpus.load();
        try (var stream = ComparisonPlanRegressionTest.class.getResourceAsStream("/chat/comparison-provider-v1.json")) {
            assertNotNull(stream);
            var root = ConversationQualityCorpus.JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(1, root.get("version").asInt());
            var result = new ArrayList<Replay>();
            for (var row : root.get("cases")) result.add(new Replay(fixtures.stream()
                    .filter(f -> f.id().equals(row.get("id").asString())).findFirst().orElseThrow(),
                    row.get("repetition").asInt(), row.get("output").asString()));
            assertEquals(9, result.size());
            assertEquals(9, result.stream().map(Replay::toString).distinct().count());
            return result;
        }
    }

    @ParameterizedTest(name = "{0}") @MethodSource("replays")
    void observedProviderOutputsKeepBothLocatorsAndExecuteWithTheCompleteOriginalQuestion(Replay replay) {
        var plan = ClassifierPlan.parse(replay.output(), replay.fixture().context());
        assertTrue(ConversationQualityCorpus.evaluate(replay.fixture(), plan).passed());
        assertTrue(plan.comparison());
        assertTrue(plan.constraints().isEmpty());
        assertTrue(plan.requests().stream().allMatch(r -> r.question() == null));
        assertEquals(plan, ClassifierPlan.parseProvider(providerWire(replay.output()), replay.fixture().context()));
    }

    private static String providerWire(String flat) {
        var root = ConversationQualityCorpus.JSON.readTree(flat);
        var wire = ConversationQualityCorpus.JSON.createObjectNode();
        var composition = wire.putObject("composition");
        composition.set("requests", root.get("requests"));
        composition.set("comparison", root.get("comparison"));
        composition.set("splitAfter", root.get("splitAfter"));
        wire.set("constraints", root.get("constraints"));
        return wire.toString();
    }

    @Test void observedCompletedComparisonWithAnIndependentBoundaryStillFailsClosed() throws Exception {
        try (var stream = getClass().getResourceAsStream("/chat/comparison-invalid-provider-v1.json")) {
            assertNotNull(stream);
            var sample = ConversationQualityCorpus.JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            var trace = sample.get("providerTrace").get(0);
            assertEquals("completed", trace.get("status").asString());
            assertTrue(trace.get("outputTokens").asInt() < 544);
            String invalid = trace.get("syntheticOutput").asString();
            var fixture = ConversationQualityCorpus.load().stream().filter(f -> f.id().equals("compare-ids")).findFirst().orElseThrow();
            assertThrows(ChatException.class, () -> ClassifierPlan.parse(invalid, fixture.context()));
            assertThrows(ChatException.class, () -> ClassifierPlan.parseProvider(providerWire(invalid), fixture.context()));
            String corrected = invalid.replace("\"splitAfter\":\"42와\"", "\"splitAfter\":null");
            assertNotEquals(invalid, corrected);
            assertTrue(ConversationQualityCorpus.evaluate(fixture,
                    ClassifierPlan.parseProvider(providerWire(corrected), fixture.context())).passed());
        }
    }

    @Test void providerCompositionCannotHideLegacyExtraDuplicateOrConflictingFields() throws Exception {
        var replay = replays().get(0);
        String valid = providerWire(replay.output());
        for (String invalid : List.of(replay.output(), valid + " trailing",
                valid.replace("\"comparison\":true", "\"comparison\":true,\"comparison\":false"),
                valid.replace("\"composition\":{", "\"composition\":{\"userId\":999,"),
                valid.replace("\"requests\":", "\"comparison\":true,\"requests\":"),
                valid.replace("\"comparison\":true", "\"comparison\":false"),
                valid.replace("\"splitAfter\":null", "\"splitAfter\":1"),
                valid.replace("\"constraints\":[]", "\"unexpected\":[]")))
            assertThrows(ChatException.class, () -> ClassifierPlan.parseProvider(invalid, replay.fixture().context()));
    }

    @Test void comparisonNeverRepairsMissingLocatorsSplitsIdentityOrRecommendationConstraints() throws Exception {
        var replay = replays().get(2); // polite IDs, not the title fixture
        assertEquals("compare-polite", replay.fixture().id());
        String valid = replay.output();
        for (String invalid : List.of(
                valid.replace("\"splitAfter\":null", "\"splitAfter\":\"및\""),
                valid.replace("\"comparison\":true", "\"comparison\":false"),
                valid.replace("\"contentId\":42", "\"contentId\":null"),
                valid.replace("\"contentId\":42", "\"contentId\":42.0"),
                valid.replace("\"title\":null", "\"title\":\"가짜 작품\""),
                valid.replace("\"referenceTurn\":null", "\"referenceTurn\":1"),
                valid.replace("\"route\":\"DETAIL\"", "\"route\":\"DETAIL\",\"userId\":999"),
                valid.replace("\"constraints\":[]", "\"constraints\":[{\"kind\":\"YEAR_MIN\",\"value\":\"2020\",\"source\":\"2020년\",\"terms\":[]}]")))
            assertEquals("AI_INVALID_RESPONSE", assertThrows(ChatException.class,
                    () -> ClassifierPlan.parse(invalid, replay.fixture().context())).code());
        // A structurally valid but semantically wrong ID must also fail the frozen oracle.
        var wrong = ClassifierPlan.parse(valid.replace("\"contentId\":73", "\"contentId\":42"), replay.fixture().context());
        assertFalse(ConversationQualityCorpus.evaluate(replay.fixture(), wrong).passed());
    }

    @Test void diagnosticCountsSemanticFailuresAndRetainsOnlyTheCorrespondingSyntheticTrace() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ClassifierDiagnosticLiveTest.selectedIds("unknown"));
        var representative = ClassifierDiagnosticLiveTest.selectedIds("representative");
        assertEquals(ConversationQualityCorpus.load().stream().limit(12).map(ConversationQualityCorpus.Fixture::id).toList(),
                ConversationQualityCorpus.load().stream().filter(f -> representative.contains(f.id()))
                        .map(ConversationQualityCorpus.Fixture::id).toList());
        var fixture = replays().get(2).fixture();
        var client = mock(AiClient.class);
        var trace = new ArrayList<Object>();
        var rows = new ArrayList<Map<String, Object>>();
        var wrong = ChatPlan.single(new ChatIntent(ChatIntent.Route.GREETING, ChatIntent.Media.ANY));
        when(client.classify(anyList(), any())).thenAnswer(call -> {
            trace.add(Map.of("syntheticOutput", "fixed-success")); return fixture.expected();
        }).thenAnswer(call -> {
            trace.add(Map.of("syntheticOutput", "fixed-invalid")); throw ChatException.invalidResponse();
        }).thenReturn(wrong).thenThrow(ChatException.busy());
        ClassifierDiagnosticLiveTest.run(client, new AiProperties(), List.of(fixture), 5, rows, trace, () -> {});
        assertEquals(List.of("PASS", "AI_INVALID_RESPONSE", "MISMATCH", "AI_BUSY", "NOT_RUN"),
                rows.stream().map(r -> r.get("status")).toList());
        assertEquals(List.of(trace.get(0)), rows.get(0).get("providerTrace"));
        assertEquals(List.of(trace.get(1)), rows.get(1).get("providerTrace"));
        assertEquals(List.of(), rows.get(2).get("providerTrace"));
        assertEquals(List.of(), rows.get(4).get("providerTrace"));
        assertEquals(false, rows.get(2).get("semanticMatch"));
        verify(client, times(4)).classify(anyList(), any());
    }
}
