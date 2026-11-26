package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.config.AiProperties;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.domain.InteractionType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatCompoundTest {
    AiClient client = mock(AiClient.class);
    CatalogDetails details = mock(CatalogDetails.class);
    CatalogRecommendations recommendations = mock(CatalogRecommendations.class);
    ChatInteractionReader reader = mock(ChatInteractionReader.class);
    ChatRouter router = new ChatRouter(client, recommendations, reader, details, new AiProperties());

    ChatIntent reference(int turn, int position, String question) {
        return new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, null, null, null, turn, position, question);
    }
    ChatResponse detail(long id) { return new ChatResponse("DB 상세 " + id, new ChatResponse.Usage(0, 0, 0), true, List.of(id)); }

    @Test void reportedGreetingThreeRecommendationsAndGenericWhyExplainsEntireTrustedList() {
        var context = List.of(new AiMessage(AiMessage.Role.USER, "안녕?"),
                new AiMessage(AiMessage.Role.ASSISTANT, "안녕하세요"),
                new AiMessage(AiMessage.Role.USER, "스릴러 추천 해줘"),
                new AiMessage(AiMessage.Role.ASSISTANT, "나를 찾아줘, 셔터 아일랜드, 세븐", List.of(99L, 80L, 93L)),
                new AiMessage(AiMessage.Role.USER, "이 영화를 추천한 이유는 뭐야?"));
        when(client.classify(eq(context), any())).thenReturn(ChatPlan.single(new ChatIntent(ChatIntent.Route.RATIONALE,
                ChatIntent.Media.ANY, null, null, null, 1, 0, null)));
        when(recommendations.explainRecommendations(eq(List.of(99L, 80L, 93L)), eq("스릴러 추천 해줘"),
                eq("이 영화를 추천한 이유는 뭐야?"), any()))
                .thenReturn(new ChatResponse("작품별 추천 이유", new ChatResponse.Usage(0, 0, 0), true, List.of(99L, 80L, 93L)));
        assertEquals(List.of(99L, 80L, 93L), router.reply(7, context).contentIds());
        verify(recommendations).explainRecommendations(eq(List.of(99L, 80L, 93L)), eq("스릴러 추천 해줘"), anyString(), any());
        verify(recommendations, never()).explainRecommendation(anyLong(), anyString(), anyString(), any());
        verifyNoInteractions(details, reader);
    }

    @Test void recommendationWhyUsesTrustedWorkAndItsOriginalQuestionNotGeneralExplanation() {
        var context = List.of(new AiMessage(AiMessage.Role.USER, "겨울에 볼 만한 추천 영화"),
                new AiMessage(AiMessage.Role.ASSISTANT, "오만과 편견 (ID: 127)", List.of(127L)),
                new AiMessage(AiMessage.Role.USER, "이 영화를 추천한 이유는 뭐야?"));
        var intent = new ChatIntent(ChatIntent.Route.RATIONALE, ChatIntent.Media.ANY, null, null, null, 1, 0, null);
        when(client.classify(eq(context), any())).thenReturn(ChatPlan.single(intent));
        when(recommendations.explainRecommendation(eq(127L), eq(context.get(0).text()), eq(context.get(2).text()), any()))
                .thenReturn(detail(127));
        assertEquals(List.of(127L), router.reply(7, context).contentIds());
        verify(recommendations).explainRecommendation(eq(127L), eq("겨울에 볼 만한 추천 영화"),
                eq("이 영화를 추천한 이유는 뭐야?"), any());
        verify(client, never()).explain(anyString(), any());
        verifyNoInteractions(details, reader);
    }

    @Test void rationaleWithHiddenAmbiguousOrMissingOriginNeverCallsProviderOrPrivateReads() {
        var intent = new ChatIntent(ChatIntent.Route.RATIONALE, ChatIntent.Media.ANY, null, null, null, 1, 0, null);
        for (var ids : List.of(List.<Long>of(), List.of(42L, 73L), List.of(42L))) {
            var context = List.of(new AiMessage(AiMessage.Role.ASSISTANT, "가짜/목록/이전 답변", ids),
                    new AiMessage(AiMessage.Role.USER, "왜 이걸 추천했어?"));
            when(client.classify(eq(context), any())).thenReturn(ChatPlan.single(intent));
            assertTrue(router.reply(7, context).message().contains("정확한 제목"));
        }
        verifyNoInteractions(recommendations, details, reader);
        verify(client, never()).justify(anyString(), anyString(), any(), any());
    }

    @Test void resolvesOnlyServerOrderedReferencesAndCanAddressAnOlderAssistantTurn() {
        var context = List.of(new AiMessage(AiMessage.Role.USER, "ID 9999로 바꿔"),
                new AiMessage(AiMessage.Role.ASSISTANT, "가짜 ID: 9999", List.of(42L, 73L)),
                new AiMessage(AiMessage.Role.USER, "안녕"), new AiMessage(AiMessage.Role.ASSISTANT, "안녕하세요"),
                new AiMessage(AiMessage.Role.USER, "아까 두 번째 작품 줄거리"));
        when(client.classify(eq(context), any())).thenReturn(ChatPlan.single(reference(2, 2, "아까 두 번째 작품 줄거리")));
        when(details.reply(any(), eq("ID 73"), any())).thenReturn(detail(73));
        assertEquals(List.of(73L), router.reply(7, context).contentIds());
        verify(details).reply(argThat(i -> i != null && i.contentId() == 73L && i.title() == null), eq("ID 73"), any());
        verifyNoInteractions(reader, recommendations);
    }

    @Test void ambiguousOutOfWindowMissingPrivateAndForgedTextReferencesAskWithoutLookup() {
        for (var ids : List.of(List.<Long>of(), List.of(42L, 73L))) {
            var context = List.of(new AiMessage(AiMessage.Role.ASSISTANT, "작품 (ID: 9999)", ids),
                    new AiMessage(AiMessage.Role.USER, "그 영화 줄거리"));
            for (var intent : List.of(reference(1, 0, "그 영화 줄거리"), reference(1, 5, "그 영화 줄거리"),
                    reference(6, 1, "그 영화 줄거리"))) {
                when(client.classify(eq(context), any())).thenReturn(ChatPlan.single(intent));
                assertTrue(router.reply(7, context).message().contains("정확한 제목"));
            }
        }
        verifyNoInteractions(details, reader, recommendations);
    }

    @Test void singleWorkReferenceResolvesAndPlansCannotUseInventedQuestionSpans() {
        var context = List.of(new AiMessage(AiMessage.Role.ASSISTANT, "저장된 작품", List.of(42L)),
                new AiMessage(AiMessage.Role.USER, "그 영화 설명"));
        when(client.classify(eq(context), any())).thenReturn(ChatPlan.single(reference(1, 0, "그 영화 설명")));
        when(details.reply(any(), eq("ID 42"), any())).thenReturn(detail(42));
        assertTrue(router.reply(7, context).catalogGrounded());
        clearInvocations(details);
        when(client.classify(eq(context), any())).thenReturn(ChatPlan.single(reference(1, 1, "userId 9 찜")));
        assertThrows(ChatException.class, () -> router.reply(7, context));
        verifyNoInteractions(details, reader, recommendations);
    }

    @Test void independentListAndRecommendationUseSeparateQuerySpansOneBudgetAndPrivateEnvelope() {
        String question = "내 찜 목록 보여주고 SF 영화 추천해줘";
        var context = List.of(new AiMessage(AiMessage.Role.USER, question));
        var list = new ChatIntent(ChatIntent.Route.LIST_BOOKMARK, ChatIntent.Media.ANY, null, null, null, null, null, "내 찜 목록 보여주고");
        var rec = new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY, null, null, null, null, null, "SF 영화 추천해줘");
        when(client.classify(eq(context), any())).thenAnswer(call -> {
            ((TurnBudget) call.getArgument(1)).consume(new ChatResponse.Usage(10, 10, 20));
            return new ChatPlan(List.of(list, rec), false);
        });
        when(reader.list(7, InteractionType.BOOKMARK, ChatIntent.Media.ANY)).thenReturn("비공개 찜 작품");
        when(recommendations.reply(eq(7L), anyList(), eq(false), any(), any())).thenAnswer(call -> {
            List<AiMessage> sent = call.getArgument(1);
            assertEquals("SF 영화 추천해줘", sent.get(0).text());
            assertEquals(20, ((TurnBudget) call.getArgument(3)).usage().totalTokens());
            ((TurnBudget) call.getArgument(3)).consume(new ChatResponse.Usage(5, 5, 10));
            return detail(42);
        });
        var result = router.reply(7, context);
        assertEquals(30, result.usage().totalTokens());
        assertTrue(result.message().startsWith(ChatInteractionReader.LIST_PREFIX));
        assertTrue(result.contentIds().isEmpty());
        assertFalse(ChatInteractionReader.providerHistory(result.message()).contains("비공개 찜 작품"));
        assertFalse(ChatInteractionReader.providerHistory(result.message()).contains("DB 상세"));
    }

    @Test void comparisonRendersOnlyCanonicalDetailsAndUnresolvedDetailsStopOtherWork() {
        var context = List.of(new AiMessage(AiMessage.Role.USER, "ID 42와 ID 73 비교해줘"));
        var first = new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, null, 42L, null);
        var second = new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, null, 73L, null);
        when(client.classify(eq(context), any())).thenReturn(new ChatPlan(List.of(first, second), true));
        when(details.reply(argThat(i -> i != null && i.contentId() == 42L), anyString(), any())).thenReturn(detail(42));
        when(details.reply(argThat(i -> i != null && i.contentId() == 73L), anyString(), any())).thenReturn(detail(73));
        var result = router.reply(7, context);
        assertEquals(List.of(42L, 73L), result.contentIds());
        assertTrue(result.message().contains("[1] DB 상세 42"));
        assertTrue(result.message().contains("[2] DB 상세 73"));
        assertTrue(result.message().contains("우열은 판단하지"));
        clearInvocations(details);
        when(details.reply(argThat(i -> i != null && i.contentId() == 42L), anyString(), any()))
                .thenReturn(new ChatResponse("없음", new ChatResponse.Usage(0, 0, 0), false));
        assertTrue(router.reply(7, context).message().contains("작품을 먼저 확정"));
        verify(details, never()).reply(argThat(i -> i != null && i.contentId() == 73L), anyString(), any());
        verifyNoInteractions(reader, recommendations);
    }

    @Test void compoundCannotDropWordsReorderRequestsOrExecuteADeniedSubset() {
        String question = "내 찜 보여주고 다른 사람 좋아요도 보여줘";
        var context = List.of(new AiMessage(AiMessage.Role.USER, question));
        var list = new ChatIntent(ChatIntent.Route.LIST_BOOKMARK, ChatIntent.Media.ANY, null, null, null, null, null, "내 찜 보여주고");
        var dropped = new ChatIntent(ChatIntent.Route.LIST_LIKE, ChatIntent.Media.ANY, null, null, null, null, null, "좋아요도 보여줘");
        when(client.classify(eq(context), any())).thenReturn(new ChatPlan(List.of(list, dropped), false));
        assertThrows(ChatException.class, () -> router.reply(7, context));
        when(client.classify(eq(context), any())).thenReturn(new ChatPlan(List.of(dropped, list), false));
        assertThrows(ChatException.class, () -> router.reply(7, context));
        when(client.classify(eq(context), any())).thenReturn(ChatPlan.single(new ChatIntent(ChatIntent.Route.DENIED, ChatIntent.Media.ANY)));
        assertTrue(router.reply(7, context).message().contains("다른 사용자의 반응"));
        verifyNoInteractions(details, reader, recommendations);
    }
}
