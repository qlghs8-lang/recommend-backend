package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.domain.Content;
import com.example.recommend.chat.repository.CatalogSnapshotRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogRecommendationsTest {
    private TurnBudget budget() { return new TurnBudget(new com.example.recommend.chat.config.AiProperties()); }
    CatalogRetriever retriever = mock(CatalogRetriever.class);
    AiClient client = mock(AiClient.class);
    CatalogSnapshotRepository contents = mock(CatalogSnapshotRepository.class);
    ChatInteractionReader interactions = mock(ChatInteractionReader.class);
    CatalogRecommendations service = new CatalogRecommendations(retriever, client, contents,
            new CatalogToolService(retriever, interactions), interactions);
    List<AiMessage> context = List.of(new AiMessage(AiMessage.Role.USER, "스릴러 추천"));
    List<CatalogCandidate> candidates = List.of(new CatalogCandidate(1, "old title", "MOVIE", "Thriller", "", ""));
    ChatResponse.Usage usage = new ChatResponse.Usage(10, 3, 13);


    private CatalogSelection selection(List<Long> ids, ChatResponse.Usage usage) {
        var reasons = new HashMap<Long, CatalogRationale>();
        for (Long id : ids) if (id != null) reasons.put(id, new CatalogRationale(List.of(), "근거가 부족해요."));
        return new CatalogSelection(ids, usage, reasons);
    }

    @Test void batchExplainsAllWorksInOriginalOrderWithOneCallAndNoPrivateRead() {
        var a = new CatalogCandidate(99, "나를 찾아줘", "MOVIE", "thriller", "", "아내의 실종 사건");
        var b = new CatalogCandidate(80, "셔터 아일랜드", "MOVIE", "thriller", "", "섬에서 벌어진 실종 사건");
        var c = new CatalogCandidate(93, "세븐", "MOVIE", "thriller", "", "연쇄 사건을 쫓는 형사들");
        for (var work : List.of(a, b, c)) when(retriever.detail(work.id())).thenReturn(Optional.of(work));
        var reason = new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, "thriller")), "요청한 스릴러 장르에 해당해요.");
        when(client.justifyMany(eq("스릴러 추천 해줘"), anyString(), eq(List.of(a, b, c)), any())).thenAnswer(call -> {
            ((TurnBudget) call.getArgument(3)).consume(usage);
            return Map.of(93L, reason, 99L, reason, 80L, reason);
        });
        var result = service.explainRecommendations(List.of(99L, 80L, 93L), "스릴러 추천 해줘", "이 영화를 추천한 이유는 뭐야?", budget());
        assertEquals(List.of(99L, 80L, 93L), result.contentIds());
        assertTrue(result.message().indexOf("[1] 나를 찾아줘") < result.message().indexOf("[2] 셔터 아일랜드"));
        assertTrue(result.message().contains("[3] 세븐"));
        assertEquals(usage, result.usage());
        verify(client, times(1)).justifyMany(anyString(), anyString(), anyList(), any());
        verify(client, never()).justify(anyString(), anyString(), any(), any());
        verifyNoInteractions(interactions, contents);
    }

    @Test void missingBatchEntriesAndChangesNeverReturnAPartialExplanation() {
        var a = new CatalogCandidate(99, "나를 찾아줘", "MOVIE", "thriller", "", "실종 사건");
        var b = new CatalogCandidate(80, "셔터 아일랜드", "MOVIE", "thriller", "", "섬의 실종 사건");
        when(retriever.detail(99)).thenReturn(Optional.of(a));
        when(retriever.detail(80)).thenReturn(Optional.of(b));
        var reason = new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, "thriller")), "스릴러");
        when(client.justifyMany(anyString(), anyString(), anyList(), any())).thenReturn(Map.of(99L, reason));
        assertThrows(ChatException.class, () -> service.explainRecommendations(List.of(99L, 80L), "스릴러", "왜?", budget()));
        when(client.justifyMany(anyString(), anyString(), anyList(), any())).thenReturn(Map.of(99L, reason, 80L, reason));
        when(retriever.detail(80)).thenReturn(Optional.of(b), Optional.empty());
        assertThrows(ChatException.class, () -> service.explainRecommendations(List.of(99L, 80L), "스릴러", "왜?", budget()));
    }

    @Test void rationaleUsesVerifiedWorkAndOriginalRequestWithoutReadingPrivateTaste() {
        var work = new CatalogCandidate(127, "오만과 편견", "MOVIE", "drama,romance", "2005-09-16", "편견을 극복하는 사랑.");
        when(retriever.detail(127)).thenReturn(Optional.of(work));
        when(client.justify(eq("겨울에 볼 만한 추천 영화"), eq("이 영화를 추천한 이유는 뭐야?"), eq(work), any()))
                .thenAnswer(call -> {
                    ((TurnBudget) call.getArgument(3)).consume(usage);
                    return new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, "romance")),
                            "로맨스를 찾는다면 고려할 수 있지만 겨울에 맞는 근거는 부족해요.");
                });
        var response = service.explainRecommendation(127, "겨울에 볼 만한 추천 영화", "이 영화를 추천한 이유는 뭐야?", budget());
        assertEquals(List.of(127L), response.contentIds());
        assertEquals(usage, response.usage());
        assertTrue(response.message().contains("오만과 편견 (ID: 127)"));
        assertTrue(response.message().contains("장르: romance"));
        assertTrue(response.message().contains("모델의 해석"));
        assertTrue(response.message().contains("겨울에 맞는 근거는 부족"));
        verifyNoInteractions(interactions, contents);
    }

    @Test void rationaleFailsOnUnverifiableEvidenceAndCatalogChangesAndSuppressesUnsupportedInterpretation() {
        var work = new CatalogCandidate(127, "작품", "MOVIE", "romance", "", "사랑 이야기");
        when(retriever.detail(127)).thenReturn(Optional.of(work));
        when(client.justify(anyString(), anyString(), eq(work), any())).thenReturn(new CatalogRationale(
                List.of(new CatalogRationale.Evidence(CatalogRationale.Field.OVERVIEW, "겨울 눈 풍경")), "추정"));
        assertThrows(ChatException.class, () -> service.explainRecommendation(127, "겨울 영화", "왜?", budget()));
        when(client.justify(anyString(), anyString(), eq(work), any())).thenReturn(new CatalogRationale(List.of(), "근거 없이 단정한 해석"));
        var result = service.explainRecommendation(127, "겨울 영화", "왜?", budget());
        assertTrue(result.message().contains("근거가 부족"));
        assertFalse(result.message().contains("근거 없이 단정"));
        when(retriever.detail(127)).thenReturn(Optional.of(work), Optional.empty());
        assertThrows(ChatException.class, () -> service.explainRecommendation(127, "겨울 영화", "왜?", budget()));
    }

    @Test void deletedAndEmptyMetadataWorksDoNotCallRationaleProvider() {
        when(retriever.detail(127)).thenReturn(Optional.empty());
        assertFalse(service.explainRecommendation(127, "겨울 영화", "왜?", budget()).catalogGrounded());
        when(retriever.detail(127)).thenReturn(Optional.of(new CatalogCandidate(127, "작품", "MOVIE", "", "", "")));
        assertTrue(service.explainRecommendation(127, "겨울 영화", "왜?", budget()).message().contains("정보가 부족"));
        verifyNoInteractions(client, interactions, contents);
    }

    @Test void emptyCatalogSkipsProviderAndNeverClaimsGrounding() {
        when(retriever.retrieve(context)).thenReturn(List.of());
        var response = service.reply(7, context, false, budget());
        assertFalse(response.catalogGrounded());
        assertEquals(0, response.usage().totalTokens());
        verifyNoInteractions(client, contents);
    }

    @Test void initialRecommendationIncludesValidatedEvidenceAndInterpretationWithoutAnotherCall() {
        when(retriever.retrieve(context)).thenReturn(candidates);
        var reason = new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, "Thriller")),
                "요청하신 스릴러 장르여서 긴장감 있는 작품을 찾을 때 고려할 수 있어요.");
        when(client.recommendWithTools(eq(context), any(), any())).thenReturn(new CatalogSelection(List.of(1L), usage, Map.of(1L, reason)));
        when(contents.findByIdIn(List.of(1L))).thenReturn(List.of(Content.builder().id(1L).title("작품").type("MOVIE").genres("Thriller").build()));
        var response = service.reply(7, context, false, budget());
        assertTrue(response.message().contains("카탈로그 근거: Thriller"));
        assertTrue(response.message().contains("추천 이유(모델의 해석): " + reason.interpretation()));
        verify(client).recommendWithTools(eq(context), any(), any());
        verifyNoMoreInteractions(client);
        // Even a once-valid quote must fail if the fresh source no longer supports it.
        when(contents.findByIdIn(List.of(1L))).thenReturn(List.of(Content.builder().id(1L).title("작품").type("MOVIE").genres("Comedy").build()));
        assertThrows(ChatException.class, () -> service.reply(7, context, false, budget()));
    }

    @Test void missingReasonsFailAndPrivateToolTextIsNotRenderedAsPublicExplanation() {
        when(retriever.retrieve(context)).thenReturn(candidates);
        when(contents.findByIdIn(List.of(1L))).thenReturn(List.of(Content.builder().id(1L).title("작품").type("MOVIE").genres("Thriller").build()));
        when(client.recommendWithTools(eq(context), any(), any())).thenReturn(new CatalogSelection(List.of(1L), usage));
        assertThrows(ChatException.class, () -> service.reply(7, context, false, budget()));
        when(interactions.read(7, Set.of(1L))).thenReturn(Map.of(1L, List.of("BOOKMARK")));
        when(client.recommendWithTools(eq(context), any(), any())).thenAnswer(call -> {
            ((CatalogTools) call.getArgument(1)).execute("get_my_interactions", "{\"contentIds\":[1]}");
            return new CatalogSelection(List.of(1L), usage, Map.of(1L, new CatalogRationale(List.of(
                    new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, "Thriller")), "개인 반응을 노출하는 해석")));
        });
        var response = service.reply(7, context, false, budget());
        assertTrue(response.message().contains("카탈로그 근거: Thriller"));
        assertFalse(response.message().contains("개인 반응을 노출하는 해석"));
    }

    @Test void winterNeverFillsResultsWithUnsupportedCozyWorksOrReusesLostEvidence() {
        var request = List.of(new AiMessage(AiMessage.Role.USER, "겨울에 보면 좋을 작품 소개 해줘"));
        var criteria = RecommendationCriteria.from(List.of(new RecommendationConstraint(RecommendationConstraint.Kind.SETTING,
                "winter", "겨울", List.of("겨울", "크리스마스"))), request, request.get(0).text());
        when(retriever.retrieve(request, criteria)).thenReturn(candidates);
        assertTrue(service.reply(7, request, false, budget(), criteria).message().contains("찾지 못했어요"));
        verifyNoInteractions(client, contents, interactions);
        var winter = new CatalogCandidate(2, "마을", "MOVIE", "Drama", "", "겨울 마을의 이야기");
        when(retriever.retrieve(request, criteria)).thenReturn(List.of(winter));
        when(client.recommendWithTools(eq(request), any(), any())).thenReturn(selection(List.of(2L), usage));
        when(contents.findByIdIn(List.of(2L))).thenReturn(List.of(Content.builder().id(2L).title("마을").type("MOVIE").overview("계절 없는 이야기").build()));
        assertThrows(ChatException.class, () -> service.reply(7, request, false, budget(), criteria));
    }

    @Test void ambiguousWinterExclusionClarifiesWithoutRetrievalOrPrivateReads() {
        var request = List.of(new AiMessage(AiMessage.Role.USER, "겨울 말고 다른 계절 작품 추천해줘"));
        var criteria = RecommendationCriteria.from(List.of(new RecommendationConstraint(RecommendationConstraint.Kind.UNVERIFIABLE,
                "excluded-setting", "겨울 말고", List.of())), request, request.get(0).text());
        assertTrue(service.reply(7, request, true, budget(), criteria).message().contains("확인하거나 보장할 수 없는 조건"));
        verifyNoInteractions(retriever, interactions, client, contents);
    }

    @Test void shorterComparisonLocksToolsToTheShortestKnownCandidateAndRejectsDurationChanges() {
        var request = List.of(new AiMessage(AiMessage.Role.USER, "더 짧은 영화 추천"));
        var criteria = RecommendationCriteria.from(List.of(new RecommendationConstraint(RecommendationConstraint.Kind.ORDER,
                "SHORTER_MOVIE", "더 짧은", List.of())), request, request.get(0).text());
        var shortWork = new CatalogCandidate(1, "짧은 영화", "MOVIE", "drama", "", "희망 이야기", 90, null);
        var longWork = new CatalogCandidate(2, "긴 영화", "MOVIE", "drama", "", "희망 이야기", 120, null);
        when(retriever.retrieve(request, criteria)).thenReturn(List.of(shortWork, longWork));
        when(retriever.detail(2)).thenReturn(Optional.of(longWork));
        when(client.recommendWithTools(eq(request), any(), any())).thenAnswer(call -> {
            CatalogTools session = call.getArgument(1);
            assertEquals(List.of(shortWork), session.candidates());
            assertEquals("{\"found\":false}", session.execute("get_content", "{\"contentId\":2}"));
            return selection(List.of(1L), usage);
        });
        when(contents.findByIdIn(List.of(1L))).thenReturn(List.of(Content.builder().id(1L).title("짧은 영화")
                .type("MOVIE").genres("drama").overview("희망 이야기").runtimeMinutes(90).build()));
        assertTrue(service.reply(7, request, false, budget(), criteria).message().contains("러닝타임: 90분"));
        when(contents.findByIdIn(List.of(1L))).thenReturn(List.of(Content.builder().id(1L).title("짧은 영화")
                .type("MOVIE").genres("drama").overview("희망 이야기").runtimeMinutes(100).build()));
        assertThrows(ChatException.class, () -> service.reply(7, request, false, budget(), criteria));
    }

    @Test void canonicalFactsAreRereadAndRenderedAfterMembershipValidation() {
        when(retriever.retrieve(context)).thenReturn(candidates);
        when(client.recommendWithTools(eq(context), any(CatalogTools.class), any(TurnBudget.class))).thenReturn(selection(List.of(1L), usage));
        when(contents.findByIdIn(List.of(1L))).thenReturn(List.of(Content.builder().id(1L).title("실제 제목")
                .type("MOVIE").genres("Thriller").overview("실제 줄거리").build()));
        var response = service.reply(7, context, false, budget());
        assertTrue(response.catalogGrounded());
        assertTrue(response.message().contains("실제 제목 (ID: 1)"));
        assertTrue(response.message().contains("실제 줄거리"));
        assertFalse(response.message().contains("old title"));
        assertEquals(usage, response.usage());
        verifyNoInteractions(interactions);
    }

    @Test void newToolCandidatesCanBeSelectedButOnlyCanonicalFactsAreRendered() {
        when(retriever.retrieve(context)).thenReturn(candidates);
        when(retriever.detail(2)).thenReturn(Optional.of(new CatalogCandidate(2, "stale", "TV", "", "", "")));
        when(client.recommendWithTools(eq(context), any(CatalogTools.class), any(TurnBudget.class))).thenAnswer(call -> {
            CatalogTools tools = call.getArgument(1);
            tools.execute("get_content", "{\"contentId\":2}");
            return selection(List.of(2L), usage);
        });
        when(contents.findByIdIn(List.of(2L))).thenReturn(List.of(Content.builder().id(2L).title("Verified new work")
                .type("TV").build()));
        var response = service.reply(7, context, false, budget());
        assertTrue(response.catalogGrounded());
        assertTrue(response.message().contains("Verified new work (ID: 2)"));
        assertFalse(response.message().contains("stale"));
    }

    @Test void rejectsUnknownDuplicateNullAndTooManyIdsBeforeLookup() {
        when(retriever.retrieve(context)).thenReturn(candidates);
        for (List<Long> ids : Arrays.asList(List.of(999L), List.of(1L, 1L), Arrays.asList((Long) null), List.of(1L, 2L, 3L, 4L))) {
            when(client.recommendWithTools(eq(context), any(CatalogTools.class), any(TurnBudget.class))).thenReturn(selection(ids, usage));
            assertEquals(502, assertThrows(ChatException.class, () -> service.reply(7, context, false, budget())).status());
        }
        verifyNoInteractions(contents);
    }

    @Test void rejectsCandidateDeletedDuringGenerationAndAllowsNoSelection() {
        when(retriever.retrieve(context)).thenReturn(candidates);
        when(client.recommendWithTools(eq(context), any(CatalogTools.class), any(TurnBudget.class))).thenReturn(selection(List.of(1L), usage));
        when(contents.findByIdIn(List.of(1L))).thenReturn(List.of());
        assertEquals(502, assertThrows(ChatException.class, () -> service.reply(7, context, false, budget())).status());
        when(client.recommendWithTools(eq(context), any(CatalogTools.class), any(TurnBudget.class))).thenReturn(selection(List.of(), usage));
        assertFalse(service.reply(7, context, false, budget()).catalogGrounded());
    }

    @Test void requestedReactionsAreDisplayedEvenWhenTheModelSkipsItsTool() {
        var request = List.of(new AiMessage(AiMessage.Role.USER, "SF 영화 추천해줘. 내 찜과 싫어요 상태도 확인해줘"));
        when(retriever.retrieve(request)).thenReturn(candidates);
        // A successful public lookup must still establish membership of every selected ID.
        when(client.recommendWithTools(eq(request), any(CatalogTools.class), any(TurnBudget.class))).thenAnswer(call -> {
            CatalogTools tools = call.getArgument(1);
            tools.execute("get_content", "{\"contentId\":2}");
            return selection(List.of(1L, 2L), usage);
        });
        when(retriever.detail(2)).thenReturn(Optional.of(new CatalogCandidate(2, "Second", "TV", "", "", "")));
        when(contents.findByIdIn(List.of(1L, 2L))).thenReturn(List.of(
                Content.builder().id(1L).title("First").type("MOVIE").overview("Overview").build(),
                Content.builder().id(2L).title("Second").type("TV").build()));
        when(interactions.read(7, Set.of(1L, 2L))).thenReturn(Map.of(1L, List.of("BOOKMARK", "DISLIKE"), 2L, List.of()));
        var response = service.reply(7, request, false, budget());
        assertTrue(response.catalogGrounded());
        assertTrue(response.message().contains("내 반응(답변 시점): 좋아요 미설정 · 찜 설정 · 싫어요 설정"));
        assertTrue(response.message().contains("카탈로그 줄거리: Overview"));
        assertTrue(response.message().contains("내 반응(답변 시점): 좋아요 미설정 · 찜 미설정 · 싫어요 미설정"));
        verify(interactions).read(7, Set.of(1L, 2L));
    }

    @Test void priorReactionRequestsDoNotTriggerUnrequestedCurrentReads() {
        var request = List.of(new AiMessage(AiMessage.Role.USER, "내 찜 상태"),
                new AiMessage(AiMessage.Role.ASSISTANT, "previous answer"),
                new AiMessage(AiMessage.Role.USER, "SF 영화 추천"));
        when(retriever.retrieve(request)).thenReturn(candidates);
        when(client.recommendWithTools(eq(request), any(CatalogTools.class), any(TurnBudget.class))).thenReturn(selection(List.of(1L), usage));
        when(contents.findByIdIn(List.of(1L))).thenReturn(List.of(Content.builder().id(1L).title("First").type("MOVIE").build()));
        assertFalse(service.reply(7, request, false, budget()).message().contains("내 반응(답변 시점)"));
        verifyNoInteractions(interactions);
    }

    @Test void missingReactionResultIsNeverClaimedAsUnset() {
        var request = List.of(new AiMessage(AiMessage.Role.USER, "스릴러 영화 찜 상태 확인"));
        when(retriever.retrieve(request)).thenReturn(candidates);
        when(client.recommendWithTools(eq(request), any(CatalogTools.class), any(TurnBudget.class))).thenReturn(selection(List.of(1L), usage));
        when(contents.findByIdIn(List.of(1L))).thenReturn(List.of(Content.builder().id(1L).title("First").type("MOVIE").build()));
        when(interactions.read(7, Set.of(1L))).thenReturn(Map.of());
        assertEquals(502, assertThrows(ChatException.class, () -> service.reply(7, request, false, budget())).status());
    }

    @Test void personalizedRankingAndVerifiedReasonsAreLocalAndDeletedCandidatesStillFail() {
        var request = List.of(new AiMessage(AiMessage.Role.USER, "내 취향에 맞는 영화 추천"));
        var taste = new ChatTaste(Map.of("sf", 3));
        when(interactions.taste(7)).thenReturn(taste);
        when(retriever.retrievePersonalized(7, request, taste)).thenReturn(candidates);
        when(contents.findByIdIn(List.of(1L))).thenReturn(List.of(Content.builder().id(1L).title("Verified")
                .type("MOVIE").genres("Science Fiction").build()));
        var response = service.reply(7, request, true, budget());
        assertTrue(response.catalogGrounded());
        assertEquals(0, response.usage().totalTokens());
        assertTrue(response.message().contains("Verified (ID: 1)"));
        assertTrue(response.message().contains("장르 점수 3"));
        assertFalse(ChatTaste.withoutSnapshots(response.message()).contains("장르 점수 3"));
        assertFalse(ChatTaste.withoutSnapshots(response.message()).contains("개인화 기준"));
        verifyNoInteractions(client);
        verify(interactions, never()).read(anyLong(), anySet());
        when(contents.findByIdIn(List.of(1L))).thenReturn(List.of());
        assertEquals(502, assertThrows(ChatException.class, () -> service.reply(7, request, true, budget())).status());
    }

    @Test void noUnreactedPersonalCandidatesReturnsClarificationWithoutProvider() {
        var request = List.of(new AiMessage(AiMessage.Role.USER, "좋아요 기반 추천"));
        var taste = new ChatTaste(Map.of());
        when(interactions.taste(7)).thenReturn(taste);
        when(retriever.retrievePersonalized(7, request, taste)).thenReturn(List.of());
        var response = service.reply(7, request, true, budget());
        assertFalse(response.catalogGrounded());
        assertTrue(response.message().contains("새 추천을 찾지 못했어요"));
        verifyNoInteractions(client, contents);
    }
}
