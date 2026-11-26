package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChatPlanTest {
    private static final String OP = """
            {"route":"DETAIL","media":"ANY","title":null,"contentId":null,"releaseYear":null,
             "referenceTurn":1,"referencePosition":2,"question":"두 번째 작품 설명"}
            """.strip();
    private static String plan(String ops) { return "{\"requests\":[" + ops + "],\"comparison\":false}"; }

    @Test void strictPlanRejectsUnboundedExecutableMalformedAndContradictoryOperations() {
        assertEquals(2, ChatPlan.parse(plan(OP)).requests().get(0).referencePosition());
        assertNull(ChatPlan.parse(plan(OP.replace("\"question\":\"두 번째 작품 설명\"", "\"question\":null")))
                .requests().get(0).question());
        for (String invalid : List.of("{}", plan(""), plan(OP + "," + OP + "," + OP),
                plan(OP).replace("false", "1"), plan(OP) + " {}", plan(OP).replace("\"requests\":", "\"sql\":"),
                plan(OP).replace("\"comparison\":false", "\"comparison\":false,\"comparison\":true"),
                plan(OP.replace("\"referenceTurn\":1", "\"referenceTurn\":7")),
                plan(OP.replace("\"referenceTurn\":1", "\"referenceTurn\":1.5")),
                plan(OP.replace("\"referencePosition\":2", "\"referencePosition\":6")),
                plan(OP.replace("\"referencePosition\":2", "\"referencePosition\":null")),
                plan(OP.replace("\"contentId\":null", "\"contentId\":42")),
                plan(OP.replace("\"referenceTurn\":1", "\"referenceTurn\":9223372036854775808")),
                plan(OP.replace("\"referenceTurn\":1", "\"referenceTurn\":1,\"referenceTurn\":2")),
                plan(OP.replace("\"question\":\"두 번째 작품 설명\"", "\"question\":\" \""))))
            assertEquals(502, assertThrows(ChatException.class, () -> ChatPlan.parse(invalid)).status(), invalid);
    }

    @Test void rejectsMixedDenialUnsupportedAndMultipleGenerativeBranchesBeforeExecution() {
        var detail = new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, "작품", null, null);
        var rec = new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY);
        for (var route : List.of(ChatIntent.Route.DENIED, ChatIntent.Route.UNSUPPORTED, ChatIntent.Route.CLARIFY))
            assertThrows(ChatException.class, () -> new ChatPlan(List.of(detail, new ChatIntent(route, ChatIntent.Media.ANY)), false));
        assertThrows(ChatException.class, () -> new ChatPlan(List.of(rec, rec), false));
        assertThrows(ChatException.class, () -> new ChatPlan(List.of(rec, new ChatIntent(ChatIntent.Route.EXPLAIN, ChatIntent.Media.ANY)), false));
        assertThrows(ChatException.class, () -> new ChatPlan(List.of(detail), true));
        assertThrows(ChatException.class, () -> new ChatPlan(List.of(detail, rec), true));
        var rationale = new ChatIntent(ChatIntent.Route.RATIONALE, ChatIntent.Media.ANY, null, null, null, 1, 0, null);
        assertThrows(ChatException.class, () -> new ChatPlan(List.of(rationale, rec), false));
        assertThrows(ChatException.class, () -> new ChatPlan(List.of(rationale, rationale), false));
        assertThrows(ChatException.class, () -> new ChatIntent(ChatIntent.Route.RATIONALE, ChatIntent.Media.ANY, "작품", null, null));
    }

    @Test void conditionJsonRejectsUnknownKindsExtraFieldsAndWrongShapes() {
        String operation = "{\"route\":\"RECOMMEND\",\"media\":\"ANY\",\"title\":null,\"contentId\":null,\"releaseYear\":null,\"referenceTurn\":null,\"referencePosition\":null,\"question\":null}";
        String condition = "{\"kind\":\"MINUTES_MAX\",\"value\":\"90\",\"source\":\"90분 이하\",\"terms\":[]}";
        String valid = "{\"requests\":[" + operation + "],\"comparison\":false,\"constraints\":[" + condition + "]}";
        assertEquals("90", ChatPlan.parse(valid).constraints().get(0).value());
        for (String invalid : List.of(valid.replace("MINUTES_MAX", "SQL"), valid.replace("\"90\"", "90"),
                valid.replace("\"terms\":[]", "\"terms\":[],\"userId\":999"), valid.replace("\"source\":\"90분 이하\"", "\"source\":null"),
                valid.replace("\"terms\":[]", "\"terms\":[null]"), valid + " trailing"))
            assertThrows(ChatException.class, () -> ChatPlan.parse(invalid), invalid);
        assertThrows(ChatException.class, () -> new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.GREETING, ChatIntent.Media.ANY)), false,
                List.of(new RecommendationConstraint(RecommendationConstraint.Kind.MINUTES_MAX, "90", "90분", List.of()))));
    }
}
