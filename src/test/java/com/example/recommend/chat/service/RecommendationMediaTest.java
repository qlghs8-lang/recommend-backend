package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationMediaTest {
    private String wire(String media, String source, String constraints) {
        return "{\"requests\":[{\"route\":\"LIST_BOOKMARK\",\"media\":\"MOVIE\"},{\"route\":\"PERSONALIZED\","
                + "\"media\":\"" + media + "\",\"mediaSource\":" + source + "}],\"splitAfter\":\"보여주고\",\"comparison\":false,\"constraints\":" + constraints + "}";
    }

    @Test void frozenOmittedSlotIsRejectedAndDedicatedMovieOrTvSlotCreatesOneServerCondition() {
        for (String type : List.of("MOVIE", "TV")) {
            String word = type.equals("MOVIE") ? "영화" : "시리즈";
            String current = "내 영화 찜 목록 보여주고 내 취향으로 새 " + word + " 추천해줘";
            var result = ClassifierPlan.parse(wire(type, "\"" + word + "\"", "[]"), current);
            assertEquals(ChatIntent.Media.MOVIE, result.requests().get(0).media());
            assertEquals(ChatIntent.Media.ANY, result.requests().get(1).media()); // Existing internal contract.
            assertEquals(List.of(new RecommendationConstraint(RecommendationConstraint.Kind.MEDIA, type, word, List.of())), result.constraints());
            assertEquals(current, result.requests().get(0).question() + result.requests().get(1).question());
        }
        String observed = """
                {"requests":[{"route":"LIST_BOOKMARK","media":"ANY"},{"route":"PERSONALIZED"}],
                 "splitAfter":"보여줘.","comparison":false,"constraints":[]}
                """;
        assertThrows(ChatException.class, () -> ClassifierPlan.parse(observed,
                "내 찜 목록을 보여줘. 그리고 내 취향으로 새 영화 추천해줘"));
    }

    @Test void missingOrForgedSourceDuplicateLegacyMediaAndTypeNullAreRejected() {
        String current = "내 영화 찜 목록 보여주고 내 취향으로 시리즈 추천해줘";
        for (String invalid : List.of(wire("TV", "null", "[]"), wire("TV", "\"없는 원문\"", "[]"),
                wire("ANY", "\"시리즈\"", "[]"), wire("TV", "\" \"", "[]"),
                wire("TV", "1", "[]"), wire("UNKNOWN", "null", "[]"),
                wire("TV", "\"시리즈\"", "[{\"kind\":\"MEDIA\",\"value\":\"MOVIE\",\"source\":\"영화\",\"terms\":[]}]"),
                wire("TV", "\"시리즈\"", "[]").replace("\"media\":\"TV\"", "\"media\":null")))
            assertThrows(ChatException.class, () -> ClassifierPlan.parse(invalid, current), invalid);
    }

    @Test void unknownMediaDoesNotBorrowAnAdjacentListTypeOrInventAFilter() {
        String current = "내 영화 찜 목록 보여주고 내 취향으로 작품 추천해줘";
        var result = ClassifierPlan.parse(wire("ANY", "null", "[]"), current);
        assertTrue(result.constraints().isEmpty());
        assertThrows(ChatException.class, () -> ClassifierPlan.parse(wire("MOVIE", "\"영화\"", "[]"), current));
    }

    @Test void carriedSourceMayUseEarlierUserTextButNeverAssistantOrAnotherOperation() {
        String current = "내 찜 목록 보여주고 이전 조건으로 내 취향 추천해줘";
        var userContext = List.of(new AiMessage(AiMessage.Role.USER, "시리즈 추천해줘"),
                new AiMessage(AiMessage.Role.ASSISTANT, "합성 추천"), new AiMessage(AiMessage.Role.USER, current));
        var result = ClassifierPlan.parse(wire("TV", "\"시리즈\"", "[]"), userContext);
        assertEquals("TV", result.constraints().get(0).value());
        var assistantOnly = List.of(new AiMessage(AiMessage.Role.ASSISTANT, "시리즈 추천"), new AiMessage(AiMessage.Role.USER, current));
        assertThrows(ChatException.class, () -> ClassifierPlan.parse(wire("TV", "\"시리즈\"", "[]"), assistantOnly));
    }
}
