package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.ChatException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExplanationPlanTest {
    private static String explanation(boolean comparison, String split) {
        return "{\"composition\":{\"requests\":[{\"route\":\"EXPLAIN\"}],\"comparison\":" + comparison
                + ",\"splitAfter\":" + split + "},\"constraints\":[]}";
    }

    @Test void observedCompletedExplanationWithComparisonTrueIsNeverRepairedOrExecuted() throws Exception {
        try (var stream = getClass().getResourceAsStream("/chat/explanation-invalid-provider-v1.json")) {
            assertNotNull(stream);
            var sample = ConversationQualityCorpus.JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            var trace = sample.get("providerTrace").get(0);
            assertEquals("completed", trace.get("status").asString());
            assertEquals(129, trace.get("outputTokens").asInt());
            var fixture = ConversationQualityCorpus.load().stream().filter(f -> f.id().equals("explain-format")).findFirst().orElseThrow();
            var historical = ConversationQualityCorpus.JSON.readTree(trace.get("syntheticOutput").asString());
            assertTrue(historical.get("composition").get("comparison").asBoolean());
            assertEquals("EXPLAIN", historical.get("requests").get(0).get("route").asString());
            // Reframe the historical layout without changing the invalid comparison value.
            var wire = ConversationQualityCorpus.JSON.createObjectNode();
            var composition = wire.putObject("composition");
            composition.set("requests", historical.get("requests"));
            composition.set("comparison", historical.get("composition").get("comparison"));
            composition.set("splitAfter", historical.get("composition").get("splitAfter"));
            wire.set("constraints", historical.get("constraints"));
            assertThrows(ChatException.class, () -> ClassifierPlan.parseProvider(wire.toString(), fixture.context()));
            composition.put("comparison", false);
            assertTrue(ConversationQualityCorpus.evaluate(fixture, ClassifierPlan.parseProvider(wire.toString(), fixture.context())).passed());
        }
    }

    @Test void generatedCompositionsCoupleCountTypeAndBoundaryWithoutChangingServerGuards() {
        var schema = ConversationQualityCorpus.JSON.readTree(ConversationQualityCorpus.JSON.writeValueAsString(
                ClassifierPlan.compositionSchema("영화와 시리즈는 어떤 차이가 있어?")));
        var branches = schema.get("anyOf"); assertEquals(3, branches.size());
        var single = branches.get(0).get("properties");
        assertEquals(1, single.get("requests").get("minItems").asInt());
        assertEquals(1, single.get("requests").get("maxItems").asInt());
        assertFalse(single.get("comparison").get("enum").get(0).asBoolean());
        assertEquals("null", single.get("splitAfter").get("type").asString());
        var compare = branches.get(1).get("properties");
        assertEquals(2, compare.get("requests").get("minItems").asInt());
        assertEquals(2, compare.get("requests").get("maxItems").asInt());
        assertEquals("DETAIL", compare.get("requests").get("items").get("properties").get("route").get("enum").get(0).asString());
        assertTrue(compare.get("comparison").get("enum").get(0).asBoolean());
        assertEquals("null", compare.get("splitAfter").get("type").asString());
        var independent = branches.get(2).get("properties");
        assertEquals(2, independent.get("requests").get("minItems").asInt());
        assertFalse(independent.get("comparison").get("enum").get(0).asBoolean());
        assertEquals("string", independent.get("splitAfter").get("type").asString());
        assertTrue(independent.get("splitAfter").get("enum").valueStream().noneMatch(n -> n.isNull()));
        assertEquals(2, ((List<?>) ClassifierPlan.compositionSchema("안녕").get("anyOf")).size());
        for (String invalid : List.of(explanation(true, "null"), explanation(false, "\"영화와\""),
                explanation(false, "null").replace("\"requests\":[", "\"userId\":999,\"requests\":["),
                explanation(false, "null") + " trailing"))
            assertThrows(ChatException.class, () -> ClassifierPlan.parseProvider(invalid,
                    List.of(new AiMessage(AiMessage.Role.USER, "영화와 시리즈는 어떤 차이가 있어?"))));
    }
}
