package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.config.AiProperties;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClassifierPlanTest {
    private static String plan(String operations, String split) {
        return "{\"requests\":[" + operations + "],\"comparison\":false,\"constraints\":[],\"splitAfter\":" + split + "}";
    }
    private static final String BOOKMARK = "{\"route\":\"LIST_BOOKMARK\",\"media\":\"ANY\"}";
    private static final String PERSONAL = "{\"route\":\"PERSONALIZED\",\"media\":\"ANY\",\"mediaSource\":null}";

    @Test void frozenAuditCompoundAndConnectorVariantsPreserveEveryCharacterAndOrder() {
        var cases = List.of(
                List.of("내 찜 목록을 보여줘. 그리고 내 취향으로 새 영화 추천해줘", "보여줘."),
                List.of("내 찜 목록을 보여줘 그리고 내 취향으로 새 영화 추천해줘", "보여줘"),
                List.of("내 찜 목록 보여주고 SF 영화 추천해줘", "보여주고"),
                List.of("내 찜 목록을 보여줘.다음으로 내 취향으로 새 영화 추천해줘", "보여줘."),
                List.of("내 취향으로 새 영화 추천해줘. 다음으로 내 찜 목록을 보여줘", "추천해줘."),
                List.of("  내 찜 목록을 보여줘!\n그리고 🎬 새 영화 추천해줘?  ", "보여줘!"),
                List.of("찜 보여줘. 그리고 좋아요 보여줘.", "그리고"));
        for (var pair : cases) {
            String current = pair.get(0), anchor = pair.get(1);
            boolean reversed = current.startsWith("내 취향");
            var result = ClassifierPlan.parse(plan(reversed ? PERSONAL + "," + BOOKMARK : BOOKMARK + "," + PERSONAL,
                    "\"" + anchor + "\""), current);
            String first = result.requests().get(0).question(), second = result.requests().get(1).question();
            assertEquals(current, first + second);
            assertTrue(first.endsWith(anchor));
            assertEquals(reversed ? ChatIntent.Route.PERSONALIZED : ChatIntent.Route.LIST_BOOKMARK, result.requests().get(0).route());
            assertEquals(reversed ? ChatIntent.Route.LIST_BOOKMARK : ChatIntent.Route.PERSONALIZED, result.requests().get(1).route());
        }
    }

    @Test void boundaryChoicesStayBoundedAndDoNotSplitUnicodeOrEnableSplitsForSingleOperations() {
        String current = "🎬추천하고 😃 찜 보여줘";
        var schema = ClassifierPlan.splitSchema(current);
        assertTrue(schema.toString().contains("🎬추천하고"));
        assertTrue(schema.toString().contains("😃"));
        assertThrows(ChatException.class, () -> ClassifierPlan.parse(plan(PERSONAL, "\"🎬추천하고\""), current));
        String longInput = java.util.stream.IntStream.range(0, 140).mapToObj(i -> "단어" + i).collect(java.util.stream.Collectors.joining(" "));
        assertEquals(Collections.singletonList(null), ClassifierPlan.splitSchema(longInput).get("enum"));
        assertNull(ClassifierPlan.parse(plan(PERSONAL, "null"), longInput).requests().get(0).question());
        for (int total : List.of(16, 64, 800, 2048, 4096)) {
            var p = new AiProperties(); p.setMaxOutputTokens(total);
            int allowance = new TurnBudget(p).classificationTokens();
            assertTrue(allowance >= 16 && allowance <= total && allowance <= 1024);
            if (total >= 800) assertTrue(total - allowance >= 256);
        }
    }

    @Test void punctuationInventionAndAmbiguousOrMissingSplitCannotReachPrivateOrCatalogReads() {
        String current = "내 찜 목록을 보여줘. 그리고 내 취향으로 새 영화 추천해줘";
        var client = mock(AiClient.class);
        var rec = mock(CatalogRecommendations.class); var reader = mock(ChatInteractionReader.class); var details = mock(CatalogDetails.class);
        var router = new ChatRouter(client, rec, reader, details, new AiProperties());
        for (String split : List.of("null", "1", "\"추천해줘.\"", "\"보여줘. 그리고 내 취향으로 새 영화 추천해줘\"", "\"\"")) {
            doAnswer(c -> ClassifierPlan.parse(plan(BOOKMARK + "," + PERSONAL, split), current))
                    .when(client).classify(anyList(), any());
            assertThrows(ChatException.class, () -> router.reply(7, List.of(new AiMessage(AiMessage.Role.USER, current))));
        }
        assertThrows(ChatException.class, () -> ClassifierPlan.parse(plan(BOOKMARK + "," + PERSONAL, "\"보여줘.\""),
                "찜 보여줘. 그리고 좋아요 보여줘."));
        verifyNoInteractions(rec, reader, details);
    }

    @Test void strictCompactFormatRejectsLegacyQuestionsExtraIdentityMixedDenialAndBadLocators() {
        String valid = plan(PERSONAL, "null");
        assertNull(ClassifierPlan.parse(valid, "내 취향으로 추천해줘").requests().get(0).question());
        for (String invalid : List.of(valid + " trailing", valid.replace("\"splitAfter\":null", "\"splitAfter\":null,\"splitAfter\":null"),
                valid.replace(PERSONAL, "{\"route\":\"PERSONALIZED\",\"question\":\"가짜 질문\"}"),
                valid.replace(PERSONAL, "{\"route\":\"PERSONALIZED\",\"userId\":999}"),
                plan("{\"route\":\"LIST_BOOKMARK\"}", "null"), plan("{\"route\":\"RATIONALE\",\"referenceTurn\":1.5,\"referencePosition\":0}", "null"),
                plan("{\"route\":\"DENIED\"}," + BOOKMARK, "\"보여줘.\""),
                plan(PERSONAL + "," + PERSONAL, "\"보여줘.\""), valid.replace("\"constraints\":[],", "")))
            assertThrows(ChatException.class, () -> ClassifierPlan.parse(invalid, "찜 보여줘. 그리고 영화 추천해줘"), invalid);
    }

    @Test void oneToEightConditionsAndShortFollowupKeepStructuredConditionsWithoutQuestionCopies() {
        var conditions = List.of(
                "{\"kind\":\"MEDIA\",\"value\":\"MOVIE\",\"source\":\"영화\",\"terms\":[]}",
                "{\"kind\":\"GENRE_ALL\",\"value\":\"comedy\",\"source\":\"코미디\",\"terms\":[]}",
                "{\"kind\":\"GENRE_EXCLUDE\",\"value\":\"horror\",\"source\":\"공포 제외\",\"terms\":[]}",
                "{\"kind\":\"YEAR_MIN\",\"value\":\"2010\",\"source\":\"2010년 이후\",\"terms\":[]}",
                "{\"kind\":\"YEAR_MAX\",\"value\":\"2025\",\"source\":\"2025년 이전\",\"terms\":[]}",
                "{\"kind\":\"MINUTES_MAX\",\"value\":\"90\",\"source\":\"한 시간 반 이하\",\"terms\":[]}",
                "{\"kind\":\"MOOD\",\"value\":\"uplifting\",\"source\":\"기분 전환\",\"terms\":[\"유쾌\"]}",
                "{\"kind\":\"ORDER\",\"value\":\"SHORTER_MOVIE\",\"source\":\"더 짧은\",\"terms\":[]}");
        for (int count = 1; count <= 8; count++) {
            var text = plan("{\"route\":\"RECOMMEND\",\"media\":\"MOVIE\",\"mediaSource\":\"영화\"}", "null")
                    .replace("\"constraints\":[]", "\"constraints\":[" + String.join(",", conditions.subList(1, count)) + "]");
            var result = ClassifierPlan.parse(text, List.of(new AiMessage(AiMessage.Role.USER,
                    "기분 전환할 코미디 영화 추천. 2010년 이후 2025년 이전, 한 시간 반 이하, 공포 제외"),
                    new AiMessage(AiMessage.Role.ASSISTANT, "합성 추천", List.of(42L)),
                    new AiMessage(AiMessage.Role.USER, "그중 더 짧은 걸로 골라줘")));
            assertEquals(count, result.constraints().size());
            assertNull(result.requests().get(0).question());
        }
    }

    @Test void comparisonUsesWholeQuestionAndRequiresTwoValidatedDetailLocators() {
        String detail = "{\"route\":\"DETAIL\",\"media\":\"ANY\",\"title\":null,\"contentId\":42,\"releaseYear\":null,\"referenceTurn\":null,\"referencePosition\":null}";
        var text = plan(detail + "," + detail.replace("42", "73"), "null").replace("\"comparison\":false", "\"comparison\":true");
        var result = ClassifierPlan.parse(text, "ID 42와 ID 73 비교해줘");
        assertTrue(result.comparison());
        assertTrue(result.requests().stream().allMatch(i -> i.question() == null));
        assertThrows(ChatException.class, () -> ClassifierPlan.parse(text.replace("42", "42.0"), "ID 42와 ID 73 비교해줘"));
    }
}
