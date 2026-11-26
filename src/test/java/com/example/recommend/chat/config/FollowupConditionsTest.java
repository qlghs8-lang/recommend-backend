package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FollowupConditionsTest {
    static List<ClassifierStabilityCorpus.Case> cases() throws Exception { return FollowupConditionsLiveTest.cases(); }
    static RecommendationConstraint c(String kind, String value, String source) {
        return new RecommendationConstraint(RecommendationConstraint.Kind.valueOf(kind), value, source,
                Set.of("MOOD", "SITUATION").contains(kind) ? List.of("유쾌") : List.of());
    }
    static ChatPlan oracle(ClassifierStabilityCorpus.Case test) throws Exception {
        var audit = ClassifierStabilityTest.oracle(cases().get(0)).constraints();
        var conditions = new ArrayList<RecommendationConstraint>();
        switch (test.id()) {
            case "audit-shorter" -> conditions.addAll(audit);
            case "fresh-mood" -> audit.stream().filter(c -> c.kind() != RecommendationConstraint.Kind.ORDER
                    && c.kind() != RecommendationConstraint.Kind.REFERENCE).forEach(conditions::add);
            case "commute-tv-shorter" -> conditions.addAll(List.of(c("MEDIA", "TV", "시리즈"),
                    c("SITUATION", "commute", "출퇴근길"), c("EPISODE_MINUTES_MAX", "20", "한 회 20분 이하"),
                    c("GENRE_ALL", "animation", "애니메이션"), c("REFERENCE", "ONLY_LAST", "그중"), c("ORDER", "SHORTER_EPISODE", "더 짧은")));
            case "mood-shorter-no-numeric-limit" -> audit.stream().filter(c -> c.kind() != RecommendationConstraint.Kind.MINUTES_MAX
                    && c.kind() != RecommendationConstraint.Kind.GENRE_EXCLUDE).forEach(conditions::add);
            case "replace-mood-with-situation" -> {
                audit.stream().filter(c -> c.kind() != RecommendationConstraint.Kind.MOOD && c.kind() != RecommendationConstraint.Kind.ORDER
                        && c.kind() != RecommendationConstraint.Kind.REFERENCE).forEach(conditions::add);
                conditions.add(c("SITUATION", "commute", "출퇴근길"));
            }
            case "reset-all" -> conditions.addAll(List.of(c("MEDIA", "MOVIE", "영화"), c("GENRE_ALL", "action", "액션")));
            default -> throw new IllegalStateException("Unknown fixed followup");
        }
        return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY)), false, conditions);
    }

    @ParameterizedTest(name = "{0}") @MethodSource("cases")
    void frozenExpectationsExecuteWithOnlyUserSourcesAndServerReferenceScope(ClassifierStabilityCorpus.Case test) throws Exception {
        assertTrue(ClassifierStabilityCorpus.issues(test, oracle(test)).isEmpty());
    }

    @Test void scorerDistinguishesEmotionFromViewingContextAndRejectsInventedLimitsOrCarriedResetConditions() throws Exception {
        for (var test : cases().subList(0, 3)) {
            var valid = oracle(test); var wrong = new ArrayList<>(valid.constraints());
            int index = java.util.stream.IntStream.range(0, wrong.size()).filter(i -> wrong.get(i).kind() == RecommendationConstraint.Kind.MOOD
                    || wrong.get(i).kind() == RecommendationConstraint.Kind.SITUATION).findFirst().orElseThrow();
            var old = wrong.get(index);
            wrong.set(index, new RecommendationConstraint(old.kind() == RecommendationConstraint.Kind.MOOD
                    ? RecommendationConstraint.Kind.SITUATION : RecommendationConstraint.Kind.MOOD, old.value(), old.source(), old.terms()));
            assertFalse(ClassifierStabilityCorpus.issues(test, new ChatPlan(valid.requests(), false, wrong)).isEmpty());
        }
        var noLimit = cases().get(3); var plan = oracle(noLimit);
        var invented = new ArrayList<>(plan.constraints()); invented.add(c("MINUTES_MAX", "60", "더 짧은"));
        assertFalse(ClassifierStabilityCorpus.issues(noLimit, new ChatPlan(plan.requests(), false, invented)).isEmpty());
        var reset = cases().get(5); var stale = new ArrayList<>(oracle(reset).constraints());
        stale.add(c("MOOD", "uplifting", "기분 전환"));
        assertFalse(ClassifierStabilityCorpus.issues(reset, new ChatPlan(plan.requests(), false, stale)).isEmpty());
        assertEquals("44f9cb9533dfb6bf6fe74a16069a1bceedfcc67f305b6a29e9a71c77467b1926", ConversationQualityCorpus.fingerprint());
        assertEquals("310a21016c28504e7a3e14ae9ebc3aba1d6ec1d21bbd6efa8c0d5f2073f6a9c7", ClassifierStabilityCorpus.fingerprint());
    }

    @Test void observedMissingSituationIsNotHiddenByValidWireOrOtherCorrectConditions() throws Exception {
        // Exact completed output from the initial diagnostic, commute-tv-shorter repetition 2.
        String output = """
                {"requests":[{"route":"RECOMMEND","media":"TV","mediaSource":"시리즈"}],"comparison":false,"splitAfter":null,"constraints":[{"kind":"GENRE_ALL","value":"animation","source":"애니메이션","terms":[]},{"kind":"EPISODE_MINUTES_MAX","value":"20","source":"한 회 20분 이하","terms":[]},{"kind":"ORDER","value":"SHORTER_EPISODE","source":"더 짧은","terms":[]},{"kind":"REFERENCE","value":"ONLY_LAST","source":"그중","terms":[]}]}
                """;
        var test = cases().get(2);
        var plan = ClassifierPlan.parse(output, test.context());
        assertEquals(List.of("CONSTRAINT_COUNT", "CONSTRAINT_SITUATION"), ClassifierStabilityCorpus.issues(test, plan));
    }
}
