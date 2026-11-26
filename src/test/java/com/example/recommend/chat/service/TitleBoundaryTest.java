package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.config.AiProperties;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TitleBoundaryTest {
    @Test void preservesWholeQuotedTitlesAndRejectsSubstringsBeforeCatalogAccess() {
        for (var pair : List.of(List.of("\"", "\""), List.of("'", "'"), List.of("“", "”"), List.of("‘", "’"),
                List.of("「", "」"), List.of("『", "』"), List.of("〈", "〉"), List.of("《", "》"))) {
            String question = pair.get(0) + "없는 작품 XYZ" + pair.get(1) + "의 줄거리 알려줘";
            assertTrue(TitleBoundary.permits(question, "없는 작품 XYZ"), question);
            assertFalse(TitleBoundary.permits(question, "XYZ"), question);
            var em = mock(EntityManager.class);
            var response = new CatalogDetails(em).reply(new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY,
                    "XYZ", null, null), question, new TurnBudget(new AiProperties()));
            assertEquals(TitleBoundary.QUESTION, response.message());
            assertFalse(response.catalogGrounded()); assertEquals(List.of(), response.contentIds());
            verifyNoInteractions(em);
        }
    }

    @Test void treatsApostrophesAsTitleDataAndPreservesPunctuationAndKoreanAttachedQuotes() {
        for (String question : List.of("\"Schindler's List\" 줄거리", "‘Schindler’s List’ 줄거리")) {
            assertTrue(TitleBoundary.permits(question, question.contains("’s") ? "Schindler’s List" : "Schindler's List"));
            assertFalse(TitleBoundary.permits(question, "List"));
        }
        assertTrue(TitleBoundary.permits("영화'없는 작품 XYZ'줄거리", "없는 작품 XYZ"));
        assertFalse(TitleBoundary.permits("영화'없는 작품 XYZ'줄거리", "XYZ"));
        assertTrue(TitleBoundary.permits("《XYZ: 두 번째, 이야기!》 설명", "XYZ: 두 번째, 이야기!"));
        assertFalse(TitleBoundary.permits("《XYZ: 두 번째, 이야기!》 설명", "XYZ"));
        assertTrue(TitleBoundary.permits("‘ 없는 작품 XYZ ’ 설명", "없는 작품 XYZ"));
    }

    @Test void nestedIncompleteAndExcessiveQuotesFailConservativelyWithoutChoosingAnotherTitle() {
        assertTrue(TitleBoundary.permits("‘The \"XYZ\" Story’ 줄거리", "The \"XYZ\" Story"));
        assertFalse(TitleBoundary.permits("‘The \"XYZ\" Story’ 줄거리", "XYZ"));
        assertFalse(TitleBoundary.permits("‘없는 작품 XYZ 줄거리", "XYZ"));
        assertFalse(TitleBoundary.permits("‘XYZ", "XYZ"));
        assertTrue(TitleBoundary.permits("XYZ 설명하고 ‘다른 질문", "XYZ"));
        assertFalse(TitleBoundary.permits("‘x’ ".repeat(129) + "XYZ 줄거리", "XYZ"));
        assertFalse(TitleBoundary.permits("‘「".repeat(17) + "XYZ", "XYZ"));
    }

    @Test void preservesSeparatelyMentionedTitlesAndDoesNotGuessUnquotedBoundaries() {
        assertTrue(TitleBoundary.permits("XYZ 줄거리 알려줘", "XYZ"));
        assertTrue(TitleBoundary.permits("없는 작품 XYZ의 줄거리 알려줘", "XYZ")); // Unquoted semantics belong to the classifier.
        assertTrue(TitleBoundary.permits("‘없는 작품 XYZ’ 말고 XYZ 줄거리", "XYZ"));
        assertTrue(TitleBoundary.permits("‘공포’가 아닌 XYZ 줄거리", "XYZ"));
        assertTrue(TitleBoundary.permits("‘XYZ’ 줄거리", "xyz"));
        assertTrue(TitleBoundary.permits("Schindler's List 줄거리", "Schindler's List"));
        assertFalse(TitleBoundary.permits("‘XYZ’ 줄거리", "ABC"));
    }

    @Test void croppedDetailStopsCompoundWorkBeforePrivateReactionListOrRecommendation() {
        var client = mock(AiClient.class); var recommendations = mock(CatalogRecommendations.class);
        var interactions = mock(ChatInteractionReader.class); var em = mock(EntityManager.class);
        String first = "‘없는 작품 XYZ’ 줄거리와"; String second = " 내 찜 목록 보여줘";
        when(client.classify(anyList(), any())).thenReturn(new ChatPlan(List.of(
                new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, "XYZ", null, null, null, null, first),
                new ChatIntent(ChatIntent.Route.LIST_BOOKMARK, ChatIntent.Media.ANY, null, null, null, null, null, second)), false));
        var response = new ChatRouter(client, recommendations, interactions, new CatalogDetails(em), new AiProperties())
                .reply(7, List.of(new AiMessage(AiMessage.Role.USER, first + second)));
        assertTrue(response.message().contains(TitleBoundary.QUESTION));
        assertEquals(List.of(), response.contentIds()); assertFalse(response.catalogGrounded());
        verifyNoInteractions(em, recommendations, interactions);
    }
}
