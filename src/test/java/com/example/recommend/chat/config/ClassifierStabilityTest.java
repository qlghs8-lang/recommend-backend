package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.ChatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClassifierStabilityTest {
    static List<ClassifierStabilityCorpus.Case> cases() throws Exception { return ClassifierStabilityCorpus.load(); }

    static ChatPlan oracle(ClassifierStabilityCorpus.Case test) {
        var constraints = test.constraints().stream().map(e -> {
            String value = e.value().equals("*") ? "uplifting" : e.value();
            String source = switch (e.kind()) {
                case "MEDIA" -> value.equals("TV") ? "시리즈" : "영화";
                case "GENRE_ALL" -> value.equals("comedy") ? "코미디" : "드라마";
                case "GENRE_EXCLUDE" -> "공포";
                case "YEAR_MIN" -> "2010년";
                case "YEAR_MAX" -> "2025년";
                case "MINUTES_MIN" -> "60분 이상";
                case "MINUTES_MAX" -> value.equals("90") ? "한 시간 반 이하" : "120분 이하";
                case "MOOD" -> "기분 전환";
                case "ORDER" -> "더 짧은";
                case "REFERENCE" -> "그중";
                default -> throw new IllegalStateException("Unknown frozen condition");
            };
            return new RecommendationConstraint(RecommendationConstraint.Kind.valueOf(e.kind()), value, source,
                    e.kind().equals("MOOD") ? List.of("유쾌") : List.of());
        }).toList();
        var current = test.context().get(test.context().size() - 1).text();
        int boundary = 0;
        if (test.routes().size() == 2) {
            String anchor = test.id().equals("compound-reversed") ? "추천해줘."
                    : test.id().equals("compound-joined") ? "보여주고"
                    : test.id().equals("compound-no-period") ? "보여줘" : "보여줘.";
            boundary = current.indexOf(anchor) + anchor.length();
        }
        var operations = new ArrayList<ChatIntent>();
        for (var route : test.routes()) operations.add(new ChatIntent(ChatIntent.Route.valueOf(route), ChatIntent.Media.ANY,
                null, null, null, null, null, boundary == 0 ? null
                : operations.isEmpty() ? current.substring(0, boundary) : current.substring(boundary)));
        return new ChatPlan(operations, false, constraints);
    }

    @ParameterizedTest(name = "{0}") @MethodSource("cases")
    void frozenOraclePreservesConditionsAndExecutesThroughCurrentServerContracts(ClassifierStabilityCorpus.Case test) {
        assertTrue(ClassifierStabilityCorpus.issues(test, oracle(test)).isEmpty(), test.id());
    }

    @Test void corpusCoversOneToEightConditionsAndShortChangesWithoutChangingOriginalCorpus() throws Exception {
        var cases = cases(); assertEquals(21, cases.size());
        assertEquals(21, cases.stream().map(ClassifierStabilityCorpus.Case::id).distinct().count());
        for (int i = 1; i <= 8; i++) {
            final int count = i;
            assertEquals(count, cases.stream().filter(c -> c.id().equals("conditions-" + count)).findFirst().orElseThrow().constraints().size());
            assertEquals(count, cases.stream().filter(c -> c.id().equals("followup-" + count)).findFirst().orElseThrow().constraints().size());
        }
        assertEquals("44f9cb9533dfb6bf6fe74a16069a1bceedfcc67f305b6a29e9a71c77467b1926", ConversationQualityCorpus.fingerprint());
        assertEquals("310a21016c28504e7a3e14ae9ebc3aba1d6ec1d21bbd6efa8c0d5f2073f6a9c7", ClassifierStabilityCorpus.fingerprint());
    }

    @Test void scorerRejectsMissingConditionsInventedSourcesAndLostCompoundOperations() throws Exception {
        var test = cases().stream().filter(c -> c.id().equals("conditions-8")).findFirst().orElseThrow();
        var valid = oracle(test);
        assertFalse(ClassifierStabilityCorpus.issues(test, new ChatPlan(valid.requests(), false, valid.constraints().subList(1, 8))).isEmpty());
        var forged = new ArrayList<>(valid.constraints());
        forged.set(0, new RecommendationConstraint(RecommendationConstraint.Kind.MEDIA, "MOVIE", "원문에 없는 조건", List.of()));
        assertTrue(ClassifierStabilityCorpus.issues(test, new ChatPlan(valid.requests(), false, forged)).contains("INVALID_CONSTRAINT_SOURCE"));
        var compound = cases().stream().filter(c -> c.id().equals("compound-period")).findFirst().orElseThrow();
        assertTrue(ClassifierStabilityCorpus.issues(compound, ChatPlan.single(new ChatIntent(ChatIntent.Route.PERSONALIZED, ChatIntent.Media.ANY)))
                .contains("ROUTE_OR_ORDER"));
    }

    @Test void runnerRecordsInitialFailureAndCircuitOpenWithoutReplacingAttempts() throws Exception {
        var test = cases().get(0); var client = mock(AiClient.class);
        when(client.classify(anyList(), any())).thenReturn(oracle(test)).thenThrow(ChatException.busy());
        var rows = new ArrayList<Map<String, Object>>();
        ClassifierStabilityLiveTest.run(client, new AiProperties(), List.of(test), rows, () -> {});
        assertEquals(List.of("PASS", "AI_BUSY", "NOT_RUN"), rows.stream().map(r -> r.get("status")).toList());
        verify(client, times(2)).classify(anyList(), any());
    }
}
