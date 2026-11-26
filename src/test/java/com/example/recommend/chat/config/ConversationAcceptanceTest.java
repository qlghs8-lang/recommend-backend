package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationAcceptanceTest {
    @Test void keepsOriginalOracleAndOnlyThreePredeclaredPolicyChanges() throws Exception {
        assertEquals(ConversationAcceptanceCorpus.ORIGINAL_HASH, ConversationQualityCorpus.fingerprint());
        var original = ConversationQualityCorpus.load(); assertEquals(61, original.size());
        var overrides = ConversationAcceptanceCorpus.root().get("overrides");
        assertEquals(Set.of("rec-tv", "detail-missing", "clarify-ranking"), new HashSet<>(overrides.propertyNames()));
        for (var fixture : original) {
            if (overrides.has(fixture.id())) {
                String current = fixture.context().get(fixture.context().size() - 1).text();
                var revised = new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY)), false, List.of(),
                        new Clarification(Clarification.Reason.valueOf(overrides.get(fixture.id()).asString()), current));
                assertEquals(List.of(), ConversationAcceptanceCorpus.revisedIssues(fixture, revised), fixture.id());
                assertFalse(ConversationAcceptanceCorpus.revisedIssues(fixture, fixture.expected()).isEmpty(), fixture.id());
            } else assertEquals(List.of(), ConversationAcceptanceCorpus.revisedIssues(fixture, fixture.expected()), fixture.id());
        }
    }
    @Test void fixedStoriesRejectDroppedLimitsWrongTitlesAndGenericClarification() throws Exception {
        var root = ConversationAcceptanceCorpus.root(); assertEquals(3, root.get("stories").size());
        int steps = 0;
        for (var story : root.get("stories")) for (var step : story.get("steps")) {
            steps++;
            var context = List.of(new AiMessage(AiMessage.Role.USER, step.get("question").asString()));
            var expected = ConversationAcceptanceCorpus.expected(step, context);
            assertEquals(List.of(), ConversationAcceptanceCorpus.planIssues(expected, expected, step.get("optionalConstraints")));
            if (expected.clarification() != null) assertFalse(ConversationAcceptanceCorpus.planIssues(expected,
                    ChatPlan.single(new ChatIntent(ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY)), null).isEmpty());
            if (!expected.constraints().isEmpty()) assertFalse(ConversationAcceptanceCorpus.planIssues(expected,
                    new ChatPlan(expected.requests(), expected.comparison()), null).isEmpty());
            if (step.get("id").asString().equals("full-title")) assertFalse(ConversationAcceptanceCorpus.planIssues(expected,
                    ChatPlan.single(new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, "XYZ", null, null)), null).isEmpty());
        }
        assertEquals(14, steps);
    }
}
