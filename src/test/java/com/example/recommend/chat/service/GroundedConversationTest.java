package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.config.AiProperties;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.domain.*;
import com.example.recommend.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "app.ai.enabled=true",
        "app.ai.global-requests-per-minute=600"}) // Shared-context service scenarios; quota boundaries live in ChatServiceTest.
@Import({ChatHistoryStore.class, ConversationService.class, ChatRouter.class, CatalogDetails.class, CatalogRecommendations.class, CatalogRetriever.class, CatalogToolService.class, ChatInteractionReader.class,
        ChatRequestLimiter.class, GroundedConversationTest.Dependencies.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GroundedConversationTest {

    private CatalogSelection selection(List<Long> ids, ChatResponse.Usage usage) {
        var reasons = new HashMap<Long, CatalogRationale>();
        for (Long id : ids) if (id != null) reasons.put(id, new CatalogRationale(List.of(), "근거가 부족해요."));
        return new CatalogSelection(ids, usage, reasons);
    }

    @TestConfiguration static class Dependencies {
        @Bean AiClient client() { return mock(AiClient.class); }
        @Bean AiProperties properties() {
            var p = new AiProperties(); p.setEnabled(true); return p;
        }
    }
    @Autowired ConversationService service;
    @Autowired ChatHistoryStore history;
    @Autowired ContentRepository contents;
    @Autowired UserRepository users;
    @Autowired AiClient client;
    @Autowired UserContentInteractionRepository interactions;
    long user, room, content;
    long otherUser;
    List<Long> interactionIds = new ArrayList<>();
    List<Long> extraContents = new ArrayList<>();
    ChatResponse.Usage usage = new ChatResponse.Usage(10, 3, 13);

    @BeforeEach void setup() {
        reset(client);
        when(client.classify(anyList(), any(TurnBudget.class))).thenAnswer(call -> {
            ((TurnBudget) call.getArgument(1)).consume(new ChatResponse.Usage(10, 3, 13));
            return ChatPlan.single(new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY));
        });
        String key = UUID.randomUUID().toString();
        user = users.saveAndFlush(User.builder().email(key + "@example.com").nickname(key).password("hash").build()).getId();
        room = history.create(user).id();
        content = contents.saveAndFlush(Content.builder().title("우주 탐험").type("MOVIE").genres("Science Fiction")
                .overview("우주를 탐험하는 이야기").build()).getId();
    }

    @AfterEach void cleanup() {
        interactions.deleteAllById(interactionIds);
        if (otherUser > 0) users.deleteById(otherUser);
        history.delete(user, room);
        users.deleteById(user);
        contents.deleteById(content);
        contents.deleteAllById(extraContents);
    }

    @Test void retrievesRealRowsWithoutHoldingTransactionAndPersistsVerifiedAnswerAndHistory() {
        when(client.recommendWithTools(anyList(), any(CatalogTools.class), any(TurnBudget.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            CatalogTools tools = call.getArgument(1);
            List<CatalogCandidate> candidates = tools.candidates();
            assertTrue(tools.execute("get_content", "{\"contentId\":" + content + "}").contains("우주 탐험"));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(List.of(content), candidates.stream().map(CatalogCandidate::id).toList());
            return selection(List.of(content), usage);
        });
        var turn = service.reply(user, room, "SF 영화 추천해줘");
        assertTrue(turn.catalogGrounded());
        assertEquals(13, turn.usage().totalTokens()); // Classifier mock consumes 13 tokens; selector mock consumes none.
        assertTrue(turn.messages().get(1).text().contains("우주 탐험 (ID: " + content + ")"));
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        service.reply(user, room, "다른 것 추천해줘");
        verify(client).recommendWithTools(argThat(messages -> messages.size() == 3
                && messages.get(0).text().equals("SF 영화 추천해줘")
                && messages.get(1).role() == AiMessage.Role.ASSISTANT), any(CatalogTools.class), any(TurnBudget.class));
    }

    @Test void pastNegativeOpinionWithANewGenreRequestKeepsCatalogFiltersAndNeverWritesReactions() {
        long tv = contents.saveAndFlush(Content.builder().title("우주 시리즈").type("TV").genres("Science Fiction")
                .overview("우주에서 일어나는 이야기").build()).getId();
        long comedy = contents.saveAndFlush(Content.builder().title("웃음 탐험").type("MOVIE").genres("Comedy")
                .overview("유쾌한 일상 이야기").build()).getId();
        extraContents.addAll(List.of(tv, comedy));
        String question = "어제 본 영화는 별로였어. 다른 SF 영화 추천해줘";
        String wire = """
                {"composition":{"requests":[{"route":"RECOMMEND","media":"MOVIE","mediaSource":"영화"}],"comparison":false,"splitAfter":null},"constraints":[{"kind":"GENRE_ALL","value":"sf","source":"SF","terms":[]}]}
                """;
        when(client.classify(anyList(), any())).thenAnswer(call -> ClassifierPlan.parseProvider(wire, call.getArgument(0)));
        when(client.recommendWithTools(anyList(), any(), any())).thenAnswer(call -> {
            var context = (List<AiMessage>) call.getArgument(0);
            assertEquals(question, context.get(context.size() - 1).text());
            assertEquals(List.of(content), ((CatalogTools) call.getArgument(1)).candidates().stream().map(CatalogCandidate::id).toList());
            return selection(List.of(content), usage);
        });
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(Long.MAX_VALUE, room, question)).status());
        verifyNoInteractions(client);
        long reactionsBefore = interactions.count();
        var turn = service.reply(user, room, question);
        assertTrue(turn.catalogGrounded());
        assertEquals(List.of(content), history.prepare(user, room, "두 번째 질문").context().get(1).contentIds());
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        assertEquals(reactionsBefore, interactions.count());
        verify(client, times(1)).classify(anyList(), any());
        verify(client, times(1)).recommendWithTools(anyList(), any(), any());
        verify(client, never()).explain(anyString(), any());
    }

    @Test void mandatoryUnidentifiedExclusionClarifiesWithoutStartingRecommendationOrChangingReactions() {
        when(client.classify(anyList(), any())).thenAnswer(call -> ClassifierPlan.parseProvider("""
                {"composition":{"requests":[{"route":"CLARIFY"}],"comparison":false,"splitAfter":null},"constraints":[]}
                """, call.getArgument(0)));
        long reactionsBefore = interactions.count();
        var turn = service.reply(user, room, "어제 본 이름 모르는 영화는 반드시 제외하고 SF 영화 추천해줘");
        assertFalse(turn.catalogGrounded());
        assertEquals(List.of(), history.prepare(user, room, "다음 질문").context().get(1).contentIds());
        assertEquals(2, history.history(user, room, null).messages().size());
        assertEquals(reactionsBefore, interactions.count());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
    }

    private static String comparisonWire(long first, long second) {
        return """
                {"requests":[
                  {"route":"DETAIL","media":"ANY","title":null,"contentId":%d,"releaseYear":null,"referenceTurn":null,"referencePosition":null},
                  {"route":"DETAIL","media":"ANY","title":null,"contentId":%d,"releaseYear":null,"referenceTurn":null,"referencePosition":null}],
                 "comparison":true,"splitAfter":null,"constraints":[]}
                """.formatted(first, second);
    }

    private static String titleWire(String title) {
        String encoded = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(title);
        return """
                {"composition":{"requests":[{"route":"DETAIL","media":"ANY","title":%s,"contentId":null,"releaseYear":null,"referenceTurn":null,"referencePosition":null}],"comparison":false,"splitAfter":null},"constraints":[]}
                """.formatted(encoded);
    }

    @Test void quotedFullTitleAndExplicitIdKeepDifferentTrustedReferencesWhenTitlesCollide() {
        long shortId = contents.saveAndFlush(Content.builder().title("XYZ").type("MOVIE").genres("Drama").overview("짧은 이름의 작품").build()).getId();
        long fullId = contents.saveAndFlush(Content.builder().title("없는 작품 XYZ").type("MOVIE").genres("Comedy").overview("긴 이름의 작품").build()).getId();
        extraContents.addAll(List.of(shortId, fullId));
        when(client.classify(anyList(), any())).thenAnswer(call -> ClassifierPlan.parseProvider(titleWire("없는 작품 XYZ"), call.getArgument(0)));
        String question = "‘없는 작품 XYZ’의 줄거리 알려줘";
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(Long.MAX_VALUE, room, question)).status());
        verifyNoInteractions(client);
        long before = interactions.count();
        var full = service.reply(user, room, question);
        assertTrue(full.catalogGrounded());
        assertTrue(full.messages().get(1).text().contains("긴 이름의 작품"));
        assertFalse(full.messages().get(1).text().contains("짧은 이름의 작품"));
        assertEquals(List.of(fullId), history.prepare(user, room, "다음 질문").context().get(1).contentIds());
        doReturn(ChatPlan.single(new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, null, shortId, null)))
                .when(client).classify(anyList(), any());
        var byId = service.reply(user, room, "ID " + shortId + " 줄거리");
        assertTrue(byId.catalogGrounded());
        assertTrue(byId.messages().get(1).text().contains("짧은 이름의 작품"));
        assertEquals(List.of(shortId), history.prepare(user, room, "다음 질문").context().get(3).contentIds());
        doReturn(new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, "없는 작품 XYZ", null, null),
                new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, "XYZ", null, null)), true))
                .when(client).classify(anyList(), any());
        var compared = service.reply(user, room, "‘없는 작품 XYZ’와 ‘XYZ’의 장르와 유형 비교해줘");
        assertTrue(compared.catalogGrounded());
        assertTrue(compared.messages().get(1).text().contains("긴 이름의 작품"));
        assertTrue(compared.messages().get(1).text().contains("짧은 이름의 작품"));
        assertEquals(List.of(fullId, shortId), history.prepare(user, room, "다음 질문").context().get(5).contentIds());
        assertEquals(compared.messages(), history.history(user, room, null).messages().subList(4, 6));
        assertEquals(before, interactions.count());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
        verify(client, never()).explain(anyString(), any());
    }

    @Test void croppedQuotedTitleClarifiesAndMissingFullTitleNeverFallsBackToRegisteredSubstring() {
        long shortId = contents.saveAndFlush(Content.builder().title("XYZ").type("MOVIE").genres("Drama").overview("짧은 이름의 작품").build()).getId();
        extraContents.add(shortId);
        when(client.classify(anyList(), any())).thenAnswer(call -> ClassifierPlan.parseProvider(titleWire("XYZ"), call.getArgument(0)));
        String question = "‘없는 작품 XYZ’의 줄거리 알려줘";
        var cropped = service.reply(user, room, question);
        assertFalse(cropped.catalogGrounded());
        assertTrue(cropped.messages().get(1).text().contains(TitleBoundary.QUESTION));
        assertFalse(cropped.messages().get(1).text().contains("짧은 이름의 작품"));
        assertEquals(List.of(), history.prepare(user, room, "다음 질문").context().get(1).contentIds());
        doAnswer(call -> ClassifierPlan.parseProvider(titleWire("없는 작품 XYZ"), call.getArgument(0)))
                .when(client).classify(anyList(), any());
        var absent = service.reply(user, room, question);
        assertFalse(absent.catalogGrounded());
        assertTrue(absent.messages().get(1).text().contains("현재 카탈로그에서 찾지 못했어요"));
        assertEquals(List.of(), history.prepare(user, room, "다음 질문").context().get(3).contentIds());
        doAnswer(call -> ClassifierPlan.parseProvider(titleWire("XYZ"), call.getArgument(0))).when(client).classify(anyList(), any());
        var exactShort = service.reply(user, room, "‘XYZ’의 줄거리 알려줘");
        assertTrue(exactShort.catalogGrounded());
        assertEquals(List.of(shortId), history.prepare(user, room, "다음 질문").context().get(5).contentIds());
        assertEquals(6, history.history(user, room, null).messages().size());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
        verify(client, never()).explain(anyString(), any());
    }

    @Test void ambiguousUnquotedTitleUsesSpecificQuestionAndQuotedAnswerResolvesExactly() {
        long fullId = contents.saveAndFlush(Content.builder().title("없는 작품 XYZ").type("MOVIE").genres("Comedy").overview("긴 이름의 작품").build()).getId();
        extraContents.add(fullId);
        when(client.classify(anyList(), any())).thenAnswer(call -> ClassifierPlan.parseProvider("""
                {"composition":{"requests":[{"route":"CLARIFY","reason":"TITLE_BOUNDARY","source":"없는 작품 XYZ"}],"comparison":false,"splitAfter":null},"constraints":[]}
                """, call.getArgument(0)));
        var clarified = service.reply(user, room, "없는 작품 XYZ의 줄거리 알려줘");
        assertFalse(clarified.catalogGrounded());
        assertEquals(TitleBoundary.QUESTION, clarified.messages().get(1).text());
        assertEquals(List.of(), history.prepare(user, room, "다음 질문").context().get(1).contentIds());
        doAnswer(call -> ClassifierPlan.parseProvider(titleWire("없는 작품 XYZ"), call.getArgument(0)))
                .when(client).classify(anyList(), any());
        var resolved = service.reply(user, room, "정확한 제목은 ‘없는 작품 XYZ’야. 줄거리 알려줘");
        assertTrue(resolved.catalogGrounded());
        assertEquals(List.of(fullId), history.prepare(user, room, "다음 질문").context().get(3).contentIds());
        assertEquals(resolved.messages(), history.history(user, room, null).messages().subList(2, 4));
        verify(client, never()).recommendWithTools(anyList(), any(), any());
    }

    @Test void qualitativeGuidancePersistsWithoutReferencesAndExplicitFactualFollowupUsesOnlySavedRows() {
        long second = contents.saveAndFlush(Content.builder().title("숲의 기록").type("TV").genres("Documentary")
                .overview("숲의 생태를 기록한다").releaseDate(java.time.LocalDate.of(2020, 6, 7)).build()).getId();
        extraContents.add(second);
        when(client.classify(anyList(), any())).thenAnswer(call -> ClassifierPlan.parseProvider("""
                {"composition":{"requests":[{"route":"CLARIFY","reason":"QUALITATIVE_COMPARISON","source":"더 뛰어난 연출"}],"comparison":false,"splitAfter":null},"constraints":[]}
                """, call.getArgument(0)));
        String question = "우주 탐험과 숲의 기록 중 더 뛰어난 연출의 작품은 뭐야?";
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(Long.MAX_VALUE, room, question)).status());
        verifyNoInteractions(client);
        long reactionsBefore = interactions.count();
        var guidance = service.reply(user, room, question);
        assertFalse(guidance.catalogGrounded());
        assertTrue(guidance.messages().get(1).text().contains("검증된 평가 자료가 없어"));
        assertFalse(guidance.messages().get(1).text().contains("숲의 생태"));
        assertEquals(guidance.messages(), history.history(user, room, null).messages());
        assertEquals(List.of(), history.prepare(user, room, "다음 질문").context().get(1).contentIds());
        doAnswer(call -> ClassifierPlan.parse(comparisonWire(content, second), (List<AiMessage>) call.getArgument(0)))
                .when(client).classify(anyList(), any());
        var compared = service.reply(user, room, "ID " + content + "와 ID " + second + "의 장르와 줄거리를 비교해줘");
        assertTrue(compared.catalogGrounded());
        assertTrue(compared.messages().get(1).text().contains("우주를 탐험하는 이야기"));
        assertTrue(compared.messages().get(1).text().contains("숲의 생태를 기록한다"));
        assertTrue(compared.messages().get(1).text().contains("우열은 판단하지 않아요"));
        assertEquals(compared.messages(), history.history(user, room, null).messages().subList(2, 4));
        assertEquals(List.of(content, second), history.prepare(user, room, "다음 질문").context().get(3).contentIds());
        assertEquals(reactionsBefore, interactions.count());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
        verify(client, never()).explain(anyString(), any());
    }

    @Test void forgedQualitativeEvidenceFailsAtomicallyInsteadOfSavingAGuideOrReferences() {
        doReturn(new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY)), false, List.of(),
                new Clarification(Clarification.Reason.QUALITATIVE_COMPARISON, "이전 턴에만 있는 평가")))
                .when(client).classify(anyList(), any());
        assertEquals("AI_INVALID_RESPONSE", assertThrows(ChatException.class,
                () -> service.reply(user, room, "우주 탐험과 숲의 기록 중 더 뛰어난 연출은?")).code());
        assertTrue(history.history(user, room, null).messages().isEmpty());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
        verify(client, never()).explain(anyString(), any());
    }

    @Test void durationClarificationPersistsWithoutReferencesAndEpisodeFollowupUsesKnownRuntimeOnly() {
        long shortTv = contents.saveAndFlush(Content.builder().title("짧은 회차").type("TV").genres("Drama")
                .overview("함께 보내는 일상").episodeRuntimeMinutes(10).build()).getId();
        long longTv = contents.saveAndFlush(Content.builder().title("긴 회차").type("TV").genres("Drama")
                .overview("함께 보내는 일상").episodeRuntimeMinutes(90).build()).getId();
        long unknownTv = contents.saveAndFlush(Content.builder().title("미등록 길이").type("TV").genres("Drama")
                .overview("함께 보내는 일상").build()).getId();
        extraContents.addAll(List.of(shortTv, longTv, unknownTv));
        when(client.classify(anyList(), any())).thenAnswer(call -> ClassifierPlan.parseProvider("""
                {"composition":{"requests":[{"route":"CLARIFY","reason":"TV_DURATION_UNIT","source":"짧게 볼 만한"}],"comparison":false,"splitAfter":null},"constraints":[]}
                """, call.getArgument(0)));
        String question = "짧게 볼 만한 시리즈 있을까?";
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(Long.MAX_VALUE, room, question)).status());
        verifyNoInteractions(client);
        long before = interactions.count();
        var clarified = service.reply(user, room, question);
        assertFalse(clarified.catalogGrounded());
        assertTrue(clarified.messages().get(1).text().contains("회당 길이가 짧은 시리즈"));
        assertFalse(clarified.messages().get(1).text().contains("제목·카탈로그 ID"));
        assertEquals(List.of(), history.prepare(user, room, "다음 질문").context().get(1).contentIds());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
        doAnswer(call -> ClassifierPlan.parseProvider("""
                {"composition":{"requests":[{"route":"RECOMMEND","media":"TV","mediaSource":"시리즈"}],"comparison":false,"splitAfter":null},"constraints":[{"kind":"ORDER","value":"SHORTER_EPISODE","source":"회당 길이가 짧은","terms":[]}]}
                """, call.getArgument(0))).when(client).classify(anyList(), any());
        when(client.recommendWithTools(anyList(), any(), any())).thenAnswer(call -> {
            assertEquals(List.of(shortTv), ((CatalogTools) call.getArgument(1)).candidates().stream().map(CatalogCandidate::id).toList());
            return selection(List.of(shortTv), usage);
        });
        var recommended = service.reply(user, room, "회당 길이가 짧은 걸로");
        assertTrue(recommended.catalogGrounded());
        assertTrue(recommended.messages().get(1).text().contains("회당 러닝타임(대표값): 10분"));
        assertEquals(recommended.messages(), history.history(user, room, null).messages().subList(2, 4));
        assertEquals(List.of(shortTv), history.prepare(user, room, "다음 질문").context().get(3).contentIds());
        assertEquals(before, interactions.count());
        verify(client, never()).explain(anyString(), any());
    }

    @Test void forgedDurationReasonFailsAtomicallyAndWholeSeriesTotalStillCannotBeVerified() {
        when(client.classify(anyList(), any())).thenReturn(new ChatPlan(
                List.of(new ChatIntent(ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY)), false, List.of(),
                new Clarification(Clarification.Reason.TV_DURATION_UNIT, "이번 질문에 없는 근거")));
        assertEquals("AI_INVALID_RESPONSE", assertThrows(ChatException.class,
                () -> service.reply(user, room, "짧은 시리즈 추천해줘")).code());
        assertEquals(0, history.history(user, room, null).messages().size());
        when(client.classify(anyList(), any())).thenAnswer(call -> ClassifierPlan.parseProvider("""
                {"composition":{"requests":[{"route":"RECOMMEND","media":"TV","mediaSource":"시리즈"}],"comparison":false,"splitAfter":null},"constraints":[{"kind":"UNVERIFIABLE","value":"series total","source":"전체 시청 시간이 3시간 이하","terms":[]}]}
                """, call.getArgument(0)));
        var turn = service.reply(user, room, "전체 시청 시간이 3시간 이하인 시리즈 추천해줘");
        assertFalse(turn.catalogGrounded());
        assertTrue(turn.messages().get(1).text().contains("확인하거나 보장할 수 없는 조건"));
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        assertEquals(List.of(), history.prepare(user, room, "다음 질문").context().get(1).contentIds());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
    }

    @Test void politeComparisonUsesBothCatalogRowsAndPersistsOnlyTrustedReferencesAfterOwnershipCheck() {
        var movie = contents.findById(content).orElseThrow();
        movie.setReleaseDate(java.time.LocalDate.of(2015, 3, 4)); contents.saveAndFlush(movie);
        long series = contents.saveAndFlush(Content.builder().title("바다의 기록").type("TV").genres("Documentary")
                .releaseDate(java.time.LocalDate.of(2021, 8, 9)).overview("바다의 생태를 기록한다").build()).getId();
        extraContents.add(series);
        String question = "ID " + content + " 및 ID " + series + "의 공개일과 유형을 대조해 주시겠어요?";
        when(client.classify(anyList(), any())).thenAnswer(call -> ClassifierPlan.parse(
                comparisonWire(content, series), (List<AiMessage>) call.getArgument(0)));
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(Long.MAX_VALUE, room, question)).status());
        verifyNoInteractions(client);
        var turn = service.reply(user, room, question);
        String answer = turn.messages().get(1).text();
        assertTrue(turn.catalogGrounded());
        assertTrue(answer.contains("우주 탐험 (ID: " + content + ") · 영화 · 공개일: 2015-03-04"));
        assertTrue(answer.contains("바다의 기록 (ID: " + series + ") · 시리즈 · 공개일: 2021-08-09"));
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        assertEquals(List.of(content, series), history.prepare(user, room, "두 번째 작품 설명").context().get(1).contentIds());
        verify(client, times(1)).classify(anyList(), any());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
        verify(client, never()).explain(anyString(), any());
    }

    @Test void malformedComparisonAndInventedLocatorSaveNoPartialTurn() {
        String question = "ID " + content + " 및 ID 9223372036854775807의 공개일과 유형을 대조해 주세요";
        String valid = comparisonWire(content, Long.MAX_VALUE);
        for (String invalid : List.of(valid.replace("\"splitAfter\":null", "\"splitAfter\":\"및\""),
                comparisonWire(content, Long.MAX_VALUE - 1))) {
            doAnswer(call -> ClassifierPlan.parse(invalid, (List<AiMessage>) call.getArgument(0)))
                    .when(client).classify(anyList(), any());
            assertEquals("AI_INVALID_RESPONSE", assertThrows(ChatException.class, () -> service.reply(user, room, question)).code());
            assertTrue(history.history(user, room, null).messages().isEmpty());
        }
        doAnswer(call -> ClassifierPlan.parse(valid, (List<AiMessage>) call.getArgument(0)))
                .when(client).classify(anyList(), any());
        var turn = service.reply(user, room, question);
        assertTrue(turn.messages().get(1).text().contains("작품을 먼저 확정"));
        assertFalse(turn.catalogGrounded());
        assertTrue(history.prepare(user, room, "그 작품 설명").context().get(1).contentIds().isEmpty());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
    }

    @Test void winterIntroductionPersistsItsReasonAndOnlySeasonallyGroundedReferences() {
        var winter = contents.saveAndFlush(Content.builder().title("하얀 마을").type("MOVIE").genres("Drama")
                .overview("겨울 마을에서 서로를 돕는 가족").rating(1.0).build()).getId();
        var cozy = contents.saveAndFlush(Content.builder().title("그림 수업").type("TV").genres("Family")
                .overview("편안한 목소리로 그림을 소개한다. 보면 마음이 편하다.").rating(10.0).build()).getId();
        extraContents.addAll(List.of(winter, cozy));
        when(client.classify(anyList(), any())).thenReturn(ChatPlan.single(new ChatIntent(ChatIntent.Route.GREETING, ChatIntent.Media.ANY)));
        service.reply(user, room, "안녕");
        when(client.classify(anyList(), any())).thenReturn(new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY)), false,
                List.of(new RecommendationConstraint(RecommendationConstraint.Kind.SETTING, "winter", "겨울", List.of("겨울", "크리스마스", "설경", "눈보라")))));
        when(client.recommendWithTools(anyList(), any(), any())).thenAnswer(call -> {
            CatalogTools tools = call.getArgument(1);
            assertEquals(List.of(winter), tools.candidates().stream().map(CatalogCandidate::id).toList());
            return new CatalogSelection(List.of(winter), usage, Map.of(winter, new CatalogRationale(List.of(
                    new CatalogRationale.Evidence(CatalogRationale.Field.OVERVIEW, "겨울 마을")), "겨울 배경을 담아 계절 분위기를 느끼며 볼 후보예요.")));
        });
        var turn = service.reply(user, room, "겨울에 보면 좋을 작품 소개 해줘");
        assertTrue(turn.messages().get(1).text().contains("추천 이유(모델의 해석): 겨울 배경"));
        assertEquals(4, history.history(user, room, null).messages().size());
        assertEquals(List.of(winter), history.prepare(user, room, "왜 골랐어?").context().get(3).contentIds());
        verify(client, times(1)).recommendWithTools(anyList(), any(), any());
        verify(client, never()).justify(anyString(), anyString(), any(), any());
        verify(client, never()).justifyMany(anyString(), anyString(), anyList(), any());
    }

    @Test void recommendationRationaleResolvesPersistedWorkAndFailureSavesNoPartialTurn() {
        when(client.recommendWithTools(anyList(), any(), any())).thenReturn(selection(List.of(content), usage));
        service.reply(user, room, "SF 영화 추천해줘");
        when(client.classify(anyList(), any())).thenReturn(ChatPlan.single(new ChatIntent(ChatIntent.Route.RATIONALE,
                ChatIntent.Media.ANY, null, null, null, 1, 0, null)));
        when(client.justify(eq("SF 영화 추천해줘"), eq("이 영화를 추천한 이유는 뭐야?"), any(), any()))
                .thenAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    CatalogCandidate work = call.getArgument(2);
                    assertEquals(content, work.id());
                    return new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, "Science Fiction")),
                            "요청한 SF 장르와 맞아요.");
                });
        var result = service.reply(user, room, "이 영화를 추천한 이유는 뭐야?");
        assertTrue(result.messages().get(1).text().contains("카탈로그에서 확인한 근거"));
        assertEquals(4, history.history(user, room, null).messages().size());
        assertEquals(List.of(content), history.prepare(user, room, "다시 설명해줘").context().get(3).contentIds());
        doThrow(ChatException.timeout()).when(client).justify(anyString(), anyString(), any(), any());
        assertEquals(504, assertThrows(ChatException.class, () -> service.reply(user, room, "왜 추천했어?")).status());
        assertEquals(4, history.history(user, room, null).messages().size());
        verify(client, never()).explain(anyString(), any());
    }

    @Test void flexibleConditionsPersistAndFollowupReplacesTheTimeLimitWithinTrustedWorks() {
        var first = contents.findById(content).orElseThrow();
        first.setGenres("comedy,drama"); first.setRuntimeMinutes(90); first.setReleaseDate(java.time.LocalDate.of(2015, 1, 1));
        first.setOverview("희망을 찾아가는 유쾌한 친구들"); contents.saveAndFlush(first);
        long second = contents.saveAndFlush(Content.builder().title("두 번째 희망").type("MOVIE").genres("comedy")
                .runtimeMinutes(110).releaseDate(java.time.LocalDate.of(2020, 1, 1)).overview("우정과 희망을 찾아가는 친구들").build()).getId();
        extraContents.add(second);
        var media = new RecommendationConstraint(RecommendationConstraint.Kind.MEDIA, "MOVIE", "영화", List.of());
        var duration = new RecommendationConstraint(RecommendationConstraint.Kind.MINUTES_MAX, "120", "두 시간 이내", List.of());
        var exclusion = new RecommendationConstraint(RecommendationConstraint.Kind.GENRE_EXCLUDE, "horror", "공포는 빼고", List.of());
        var mood = new RecommendationConstraint(RecommendationConstraint.Kind.MOOD, "uplifting", "기분 전환", List.of("희망", "유쾌"));
        var initial = List.of(media, duration, exclusion, mood);
        when(client.classify(anyList(), any())).thenReturn(new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY)), false, initial));
        var reason = new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.OVERVIEW, "희망")), "희망을 찾는 이야기라 기분 전환을 원할 때 고려할 만해요.");
        when(client.recommendWithTools(anyList(), any(), any())).thenReturn(new CatalogSelection(List.of(content, second), usage, Map.of(content, reason, second, reason)));
        var result = service.reply(user, room, "기분 전환할 영화 추천. 두 시간 이내, 공포는 빼고");
        assertTrue(result.messages().get(1).text().contains("러닝타임: 90분"));
        assertTrue(result.messages().get(1).text().contains("모델의 해석"));
        var followup = List.of(media, exclusion, mood, new RecommendationConstraint(RecommendationConstraint.Kind.MINUTES_MAX, "90", "90분 이하", List.of()),
                new RecommendationConstraint(RecommendationConstraint.Kind.REFERENCE, "ONLY_LAST", "그중", List.of()));
        when(client.classify(anyList(), any())).thenReturn(new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY)), false, followup));
        when(client.recommendWithTools(anyList(), any(), any())).thenAnswer(call -> {
            assertEquals(List.of(content), ((CatalogTools) call.getArgument(1)).candidates().stream().map(CatalogCandidate::id).toList());
            return new CatalogSelection(List.of(content), usage, Map.of(content, reason));
        });
        service.reply(user, room, "그중 90분 이하로 골라줘");
        assertEquals(4, history.history(user, room, null).messages().size());
        assertEquals(List.of(content), history.prepare(user, room, "왜?").context().get(3).contentIds());
        // Duration changed during generation: reject before saving either message.
        doAnswer(call -> {
            var changed = contents.findById(content).orElseThrow(); changed.setRuntimeMinutes(130); contents.saveAndFlush(changed);
            return new CatalogSelection(List.of(content), usage, Map.of(content, reason));
        }).when(client).recommendWithTools(anyList(), any(), any());
        assertThrows(ChatException.class, () -> service.reply(user, room, "그중 90분 이하로 다시 골라줘"));
        assertEquals(4, history.history(user, room, null).messages().size());
    }

    @Test void shorterFollowupCarriesMoodAndHardConditionsAndChoosesOnlyTheShortestPriorPublicWork() {
        var first = contents.findById(content).orElseThrow();
        first.setGenres("comedy"); first.setRuntimeMinutes(90); first.setOverview("유쾌한 친구들이 희망을 찾는다"); contents.saveAndFlush(first);
        long shorter = contents.saveAndFlush(Content.builder().title("짧은 희망").type("MOVIE").genres("comedy")
                .runtimeMinutes(60).overview("유쾌한 이웃들이 서로 돕는다").build()).getId();
        long outside = contents.saveAndFlush(Content.builder().title("목록 밖의 짧은 희망").type("MOVIE").genres("comedy")
                .runtimeMinutes(30).overview("유쾌한 가족의 이야기").build()).getId();
        extraContents.addAll(List.of(shorter, outside));
        String initial = "우울해서 기분 전환할 영화 추천해줘. 한 시간 반 이하로, 공포는 빼고";
        String originalWire = """
                {"requests":[{"route":"RECOMMEND","media":"MOVIE","mediaSource":"영화"}],"comparison":false,"splitAfter":null,
                 "constraints":[{"kind":"MOOD","value":"uplifting","source":"기분 전환","terms":["유쾌"]},
                   {"kind":"MINUTES_MAX","value":"90","source":"한 시간 반 이하","terms":[]},
                   {"kind":"GENRE_EXCLUDE","value":"horror","source":"공포는 빼고","terms":[]}]}
                """;
        String followupWire = originalWire.replace("\"constraints\":[", """
                "constraints":[{"kind":"REFERENCE","value":"ONLY_LAST","source":"그중","terms":[]},
                  {"kind":"ORDER","value":"SHORTER_MOVIE","source":"더 짧은","terms":[]},
                """);
        when(client.classify(anyList(), any())).thenAnswer(call -> {
            List<AiMessage> context = call.getArgument(0);
            return ClassifierPlan.parse(context.size() == 1 ? originalWire : followupWire, context);
        });
        var rounds = new java.util.concurrent.atomic.AtomicInteger();
        when(client.recommendWithTools(anyList(), any(), any())).thenAnswer(call -> {
            CatalogTools tools = call.getArgument(1);
            var ids = rounds.getAndIncrement() == 0 ? List.of(content, shorter) : List.of(shorter);
            if (ids.size() == 1) {
                assertEquals(List.of(shorter), tools.candidates().stream().map(CatalogCandidate::id).toList());
                assertTrue(tools.constraints().stream().anyMatch(c -> c.kind() == RecommendationConstraint.Kind.MOOD));
                assertTrue(tools.constraints().stream().anyMatch(c -> c.kind() == RecommendationConstraint.Kind.MINUTES_MAX && c.value().equals("90")));
                assertTrue(tools.constraints().stream().anyMatch(c -> c.kind() == RecommendationConstraint.Kind.GENRE_EXCLUDE && c.value().equals("horror")));
            }
            var reason = new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, "comedy")), "코미디 장르를 담은 후보예요.");
            var reasons = new HashMap<Long, CatalogRationale>(); ids.forEach(id -> reasons.put(id, reason));
            return new CatalogSelection(ids, usage, reasons);
        });
        service.reply(user, room, initial);
        var turn = service.reply(user, room, "그중 더 짧은 걸로 골라줘");
        assertTrue(turn.catalogGrounded());
        assertTrue(turn.messages().get(1).text().contains("러닝타임: 60분"));
        assertEquals(4, history.history(user, room, null).messages().size());
        assertEquals(List.of(shorter), history.prepare(user, room, "왜 골랐어?").context().get(3).contentIds());
        verify(client, times(2)).recommendWithTools(anyList(), any(), any());
    }

    @Test void greetingThreeRecommendationsAndWhyPersistsOneCompleteGroupWithStableReferences() {
        var first = contents.findById(content).orElseThrow(); first.setGenres("Thriller"); contents.saveAndFlush(first);
        long second = contents.saveAndFlush(Content.builder().title("섬의 실종").type("MOVIE").genres("Thriller").overview("섬의 사건").build()).getId();
        long third = contents.saveAndFlush(Content.builder().title("형사 기록").type("MOVIE").genres("Thriller").overview("범인을 추적").build()).getId();
        extraContents.addAll(List.of(second, third));
        var ids = List.of(content, second, third);
        var reasonPlan = ChatPlan.single(new ChatIntent(ChatIntent.Route.RATIONALE, ChatIntent.Media.ANY, null, null, null, 1, 0, null));
        when(client.classify(anyList(), any())).thenReturn(ChatPlan.single(new ChatIntent(ChatIntent.Route.GREETING, ChatIntent.Media.ANY)),
                ChatPlan.single(new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY)), reasonPlan);
        when(client.recommendWithTools(anyList(), any(), any())).thenReturn(selection(ids, usage));
        when(client.justifyMany(eq("스릴러 추천 해줘"), eq("이 영화를 추천한 이유는 뭐야?"), anyList(), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            List<CatalogCandidate> works = call.getArgument(2);
            assertEquals(ids, works.stream().map(CatalogCandidate::id).toList());
            var result = new HashMap<Long, CatalogRationale>();
            works.forEach(work -> result.put(work.id(), new CatalogRationale(List.of(
                    new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, "Thriller")), "요청한 스릴러 장르에 해당해요.")));
            return result;
        });
        service.reply(user, room, "안녕?");
        service.reply(user, room, "스릴러 추천 해줘");
        var result = service.reply(user, room, "이 영화를 추천한 이유는 뭐야?");
        assertTrue(result.messages().get(1).text().contains("[3] 형사 기록"));
        assertEquals(6, history.history(user, room, null).messages().size());
        assertEquals(ids, history.prepare(user, room, "두 번째 줄거리").context().get(5).contentIds());
        doThrow(ChatException.timeout()).when(client).justifyMany(anyString(), anyString(), anyList(), any());
        assertThrows(ChatException.class, () -> service.reply(user, room, "다시 이유 알려줘"));
        assertEquals(6, history.history(user, room, null).messages().size());
    }

    @Test void hallucinatedOrDeletedIdsNeverSaveIncompleteTurns() {
        when(client.recommendWithTools(anyList(), any(CatalogTools.class), any(TurnBudget.class))).thenReturn(selection(List.of(Long.MAX_VALUE), usage));
        assertEquals(502, assertThrows(ChatException.class, () -> service.reply(user, room, "SF 영화 추천")).status());
        assertTrue(history.history(user, room, null).messages().isEmpty());
        when(client.recommendWithTools(anyList(), any(CatalogTools.class), any(TurnBudget.class))).thenAnswer(call -> {
            contents.deleteById(content);
            return selection(List.of(content), usage);
        });
        assertEquals(502, assertThrows(ChatException.class, () -> service.reply(user, room, "SF 영화 추천")).status());
        assertEquals(0, history.history(user, room, null).room().messageCount());
    }

    @Test void emptyRetrievalSavesAnHonestResponseWithOnlyClassification() {
        var turn = service.reply(user, room, "없는제목xyz 영화 추천");
        assertFalse(turn.catalogGrounded());
        assertEquals(13, turn.usage().totalTokens());
        assertEquals(2, history.history(user, room, null).messages().size());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
    }

    @Test void detailUsesCanonicalDatabaseFactsAndPersistsWithoutRecommendationOrPrivateReads() {
        when(client.classify(anyList(), any())).thenReturn(ChatPlan.single(new ChatIntent(ChatIntent.Route.DETAIL,
                ChatIntent.Media.ANY, "우주 탐험", null, null)));
        var turn = service.reply(user, room, "우주 탐험 줄거리 알려줘");
        assertTrue(turn.catalogGrounded());
        assertTrue(turn.messages().get(1).text().contains("우주 탐험 (ID: " + content + ")"));
        assertTrue(turn.messages().get(1).text().contains("우주를 탐험하는 이야기"));
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
        verify(client, never()).explain(anyString(), any());
    }

    @Test void detailAndIndependentRecommendationPersistTogetherWithCanonicalReferenceOrder() {
        String question = "우주 탐험 설명하고 SF 영화 추천해줘";
        var detail = new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, "우주 탐험", null, null,
                null, null, "우주 탐험 설명하고");
        var recommendation = new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY, null, null, null,
                null, null, "SF 영화 추천해줘");
        when(client.classify(anyList(), any())).thenReturn(new ChatPlan(List.of(detail, recommendation), false));
        when(client.recommendWithTools(anyList(), any(), any())).thenAnswer(call -> {
            List<AiMessage> sent = call.getArgument(0);
            assertEquals("SF 영화 추천해줘", sent.get(sent.size() - 1).text());
            return selection(List.of(content), usage);
        });
        var result = service.reply(user, room, question);
        String text = result.messages().get(1).text();
        assertTrue(text.contains("[1] 현재 카탈로그에 저장된 작품 정보"));
        assertTrue(text.contains("[2] 현재 카탈로그에서 찾은 추천 작품"));
        assertEquals(result.messages(), history.history(user, room, null).messages());
        assertEquals(List.of(content), history.prepare(user, room, "그 영화 설명").context().get(1).contentIds());
        doThrow(ChatException.timeout()).when(client).recommendWithTools(anyList(), any(), any());
        assertEquals(504, assertThrows(ChatException.class, () -> service.reply(user, room, question)).status());
        assertEquals(2, history.history(user, room, null).room().messageCount());
    }

    @Test void publicReferencesSurviveReloadAndDeletedWorksAreRecheckedForFollowups() {
        when(client.classify(anyList(), any())).thenReturn(ChatPlan.single(new ChatIntent(ChatIntent.Route.DETAIL,
                ChatIntent.Media.ANY, "우주 탐험", null, null)));
        service.reply(user, room, "우주 탐험 줄거리");
        var context = history.prepare(user, room, "그 영화 다시 설명해줘").context();
        assertEquals(List.of(content), context.get(1).contentIds());
        when(client.classify(anyList(), any())).thenReturn(ChatPlan.single(new ChatIntent(ChatIntent.Route.DETAIL,
                ChatIntent.Media.ANY, null, null, null, 1, 0, "그 영화 다시 설명해줘")));
        var second = service.reply(user, room, "그 영화 다시 설명해줘");
        assertTrue(second.messages().get(1).text().contains("우주를 탐험하는 이야기"));
        assertEquals(4, history.history(user, room, null).messages().size());
        contents.deleteById(content);
        var deleted = service.reply(user, room, "그 영화 다시 설명해줘");
        assertFalse(deleted.catalogGrounded());
        assertTrue(deleted.messages().get(1).text().contains("찾지 못했어요"));
        assertTrue(history.prepare(user, room, "설명").context().get(5).contentIds().isEmpty());
        // Restore only for the existing shared cleanup.
        content = contents.saveAndFlush(Content.builder().title("cleanup").type("MOVIE").build()).getId();
    }

    @Test void compoundListIsPrivateInFutureContextAndSecondFailureSavesNeitherAnswer() {
        var question = "내 찜 목록 보여주고 누아르 설명해줘";
        var list = new ChatIntent(ChatIntent.Route.LIST_BOOKMARK, ChatIntent.Media.ANY, null, null, null, null, null, "내 찜 목록 보여주고");
        var explanation = new ChatIntent(ChatIntent.Route.EXPLAIN, ChatIntent.Media.ANY, null, null, null, null, null, "누아르 설명해줘");
        when(client.classify(anyList(), any())).thenReturn(new ChatPlan(List.of(list, explanation), false));
        when(client.explain(eq("누아르 설명해줘"), any())).thenThrow(ChatException.timeout());
        assertEquals(504, assertThrows(ChatException.class, () -> service.reply(user, room, question)).status());
        assertEquals(0, history.history(user, room, null).room().messageCount());
        when(client.explain(eq("누아르 설명해줘"), any())).thenReturn(new ChatResponse("일반 설명 답변", usage, false));
        var result = service.reply(user, room, question);
        assertEquals(2, result.messages().size());
        assertTrue(result.messages().get(1).text().contains("일반 설명 답변"));
        var next = history.prepare(user, room, "안녕").context();
        assertFalse(next.get(1).text().contains("일반 설명 답변"));
        assertTrue(next.get(1).contentIds().isEmpty());
        clearInvocations(client);
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(Long.MAX_VALUE, room, question)).status());
        verifyNoInteractions(client);
    }

    @Test void auditCompoundFromCompactWirePlanPersistsAtomicallyAndRejectsAlteredBoundary() {
        String question = "내 찜 목록을 보여줘. 그리고 내 취향으로 새 영화 추천해줘";
        String wire = """
                {"requests":[{"route":"LIST_BOOKMARK","media":"ANY"},{"route":"PERSONALIZED","media":"MOVIE","mediaSource":"영화"}],
                 "comparison":false,"splitAfter":"보여줘.",
                 "constraints":[]}
                """;
        when(client.classify(anyList(), any())).thenAnswer(c -> ClassifierPlan.parse(wire.replace("보여줘.", "보여줘.."), question));
        assertEquals(502, assertThrows(ChatException.class, () -> service.reply(user, room, question)).status());
        assertEquals(0, history.history(user, room, null).room().messageCount());
        doAnswer(c -> ClassifierPlan.parse(wire, question)).when(client).classify(anyList(), any());
        var turn = service.reply(user, room, question);
        assertEquals(2, turn.messages().size());
        assertTrue(turn.messages().get(1).text().contains("[2]"));
        assertTrue(turn.messages().get(1).text().contains("우주 탐험"));
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        assertTrue(history.prepare(user, room, "안녕").context().get(1).contentIds().isEmpty());
        when(client.classify(anyList(), any())).thenAnswer(c -> ClassifierPlan.parse(wire.replace("보여줘.", "보여줘.."), question));
        assertEquals(502, assertThrows(ChatException.class, () -> service.reply(user, room, question)).status());
        assertEquals(2, history.history(user, room, null).room().messageCount());
    }

    @Test void movieListAndPersonalTvRecommendationKeepDifferentMediaFiltersThroughSqlAndStorage() {
        long tv = contents.saveAndFlush(Content.builder().title("별빛 시리즈").type("TV").genres("drama")
                .overview("별빛 아래 가족의 이야기").build()).getId();
        extraContents.add(tv);
        String question = "내 영화 찜 목록 보여주고 내 취향으로 시리즈 추천해줘";
        String wire = """
                {"requests":[{"route":"LIST_BOOKMARK","media":"MOVIE"},
                 {"route":"PERSONALIZED","media":"TV","mediaSource":"시리즈"}],
                 "comparison":false,"splitAfter":"보여주고","constraints":[]}
                """;
        when(client.classify(anyList(), any())).thenAnswer(c -> ClassifierPlan.parse(wire, question));
        var turn = service.reply(user, room, question);
        String recommendation = turn.messages().get(1).text().split("\\[2\\]", 2)[1];
        assertTrue(recommendation.contains("별빛 시리즈 (ID: " + tv + ")"));
        assertFalse(recommendation.contains("우주 탐험 (ID: " + content + ")"));
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        assertTrue(history.prepare(user, room, "다음 질문").context().get(1).contentIds().isEmpty());
    }

    @Test void explanationPersistsUnverifiedLabelAndFailureLeavesNoPartialTurn() {
        when(client.classify(anyList(), any())).thenAnswer(call -> ClassifierPlan.parseProvider("""
                {"composition":{"requests":[{"route":"EXPLAIN"}],"comparison":false,"splitAfter":null},"constraints":[]}
                """, call.getArgument(0)));
        when(client.explain(eq("영화와 시리즈는 어떤 차이가 있어?"), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new ChatResponse("영화와 시리즈의 일반적인 형식 차이 설명이에요.", usage, false);
        });
        var turn = service.reply(user, room, "영화와 시리즈는 어떤 차이가 있어?");
        assertFalse(turn.catalogGrounded());
        assertTrue(turn.messages().get(1).text().contains("카탈로그 정보나 최신 사실"));
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        assertTrue(history.prepare(user, room, "다음 질문").context().get(1).contentIds().isEmpty());
        when(client.explain(anyString(), any())).thenThrow(ChatException.timeout());
        assertEquals(504, assertThrows(ChatException.class, () -> service.reply(user, room, "몽타주가 뭐야?")).status());
        assertEquals(2, history.history(user, room, null).messages().size());
        clearInvocations(client);
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(Long.MAX_VALUE, room, "누아르가 뭐야?")).status());
        verifyNoInteractions(client);
    }

    @Test void listPersistsReloadsAndEntirePrivateAnswerIsAbsentFromFutureModelContext() {
        var owner = users.findById(user).orElseThrow();
        var work = contents.findById(content).orElseThrow();
        var event = interactions.saveAndFlush(UserContentInteraction.builder().user(owner).content(work)
                .eventType(InteractionType.DISLIKE).createdAt(java.time.LocalDateTime.now()).build());
        interactionIds.add(event.getId());
        when(client.classify(anyList(), any())).thenReturn(ChatPlan.single(new ChatIntent(ChatIntent.Route.LIST_DISLIKE, ChatIntent.Media.ANY)));
        String question = "내가 별로라고 표시한 작품 보여줘";
        var turn = service.reply(user, room, question);
        assertTrue(turn.messages().get(1).text().contains("우주 탐험"));
        assertTrue(turn.messages().get(1).text().contains("싫어요"));
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        var next = history.prepare(user, room, "스릴러 추천").context();
        assertEquals(question, next.get(0).text());
        assertFalse(next.get(1).text().contains("우주 탐험"));
        assertFalse(next.get(1).text().contains("ID:"));
        verify(client, never()).recommendWithTools(anyList(), any(), any());
        clearInvocations(client);
        when(client.classify(anyList(), any())).thenAnswer(call -> {
            List<AiMessage> sent = call.getArgument(0);
            assertEquals(next, sent);
            assertFalse(sent.toString().contains("우주 탐험"));
            return ChatPlan.single(new ChatIntent(ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY));
        });
        service.reply(user, room, "스릴러 추천");
        verify(client).classify(eq(next), any());
    }

    @Test void classificationFailureStoresNeitherMessageAndForeignRoomNeverClassifies() {
        when(client.classify(anyList(), any())).thenThrow(ChatException.invalidResponse());
        assertEquals(502, assertThrows(ChatException.class, () -> service.reply(user, room, "목록 보여줘")).status());
        assertEquals(0, history.history(user, room, null).room().messageCount());
        clearInvocations(client);
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(Long.MAX_VALUE, room, "목록 보여줘")).status());
        verifyNoInteractions(client);
    }

    @Test void toolFailuresSaveNoTurnAndForeignRoomsNeverStartTheToolLoop() {
        when(client.recommendWithTools(anyList(), any(CatalogTools.class), any(TurnBudget.class))).thenAnswer(call -> {
            CatalogTools tools = call.getArgument(1);
            tools.execute("get_content", "{\"contentId\":" + content + "}");
            throw ChatException.busy();
        });
        assertEquals(429, assertThrows(ChatException.class, () -> service.reply(user, room, "SF 영화 추천")).status());
        assertTrue(history.history(user, room, null).messages().isEmpty());
        clearInvocations(client);
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(Long.MAX_VALUE, room, "SF 영화 추천")).status());
        verifyNoInteractions(client);
    }

    @Test void approvedReactionToolUsesTheRoomOwnerAndPersistsVerifiedStatusButDoesNotReplayIt() {
        var owner = users.findById(user).orElseThrow();
        String key = UUID.randomUUID().toString();
        var other = users.saveAndFlush(User.builder().email(key + "@example.com").nickname(key).password("hash").build());
        otherUser = other.getId();
        var work = contents.findById(content).orElseThrow();
        var events = interactions.saveAllAndFlush(List.of(
                UserContentInteraction.builder().user(owner).content(work).eventType(InteractionType.BOOKMARK)
                        .createdAt(java.time.LocalDateTime.now()).build(),
                UserContentInteraction.builder().user(owner).content(work).eventType(InteractionType.VIEW)
                        .createdAt(java.time.LocalDateTime.now()).build(),
                UserContentInteraction.builder().user(other).content(work).eventType(InteractionType.DISLIKE)
                        .createdAt(java.time.LocalDateTime.now()).build()));
        events.forEach(e -> interactionIds.add(e.getId()));
        when(client.recommendWithTools(anyList(), any(CatalogTools.class), any(TurnBudget.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            CatalogTools tools = call.getArgument(1);
            assertEquals("{\"" + content + "\":[\"BOOKMARK\"]}",
                    tools.execute("get_my_interactions", "{\"contentIds\":[" + content + "]}"));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return selection(List.of(content), usage);
        });
        var turn = service.reply(user, room, "SF 영화 중 내가 찜한 작품 추천");
        assertTrue(turn.catalogGrounded());
        assertEquals(2, turn.messages().size());
        assertTrue(turn.messages().get(1).text().contains("우주 탐험"));
        assertTrue(turn.messages().get(1).text().contains("내 반응(답변 시점): 좋아요 미설정 · 찜 설정 · 싫어요 미설정"));
        assertFalse(turn.messages().get(1).text().contains("BOOKMARK"));
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        var nextContext = history.prepare(user, room, "SF 영화 추천").context();
        assertTrue(nextContext.get(1).text().contains("우주 탐험"));
        assertFalse(nextContext.get(1).text().contains("내 반응(답변 시점)"));
        assertEquals(3, interactions.findAllById(interactionIds).size());
    }

    @Test void personalRequestPersistsLocalReasonsAfterClassificationAndRecomputesAfterReactionRemoval() {
        when(client.classify(anyList(), any(TurnBudget.class))).thenAnswer(call -> {
            ((TurnBudget) call.getArgument(1)).consume(new ChatResponse.Usage(10, 3, 13));
            return ChatPlan.single(new ChatIntent(ChatIntent.Route.PERSONALIZED, ChatIntent.Media.ANY));
        });
        var owner = users.findById(user).orElseThrow();
        var work = contents.findById(content).orElseThrow();
        var anchor = contents.saveAndFlush(Content.builder().title("좋아요 원작").type("MOVIE").genres("SF").build());
        extraContents.add(anchor.getId());
        var event = interactions.saveAndFlush(UserContentInteraction.builder().user(owner).content(anchor)
                .eventType(InteractionType.LIKE).createdAt(java.time.LocalDateTime.now()).build());
        interactionIds.add(event.getId());
        String question = "내가 좋아요를 누른 장르 기반 작품 추천해줘";
        var turn = service.reply(user, room, question);
        assertTrue(turn.catalogGrounded());
        assertEquals(13, turn.usage().totalTokens());
        String text = turn.messages().get(1).text();
        assertTrue(text.contains(work.getTitle()));
        assertTrue(text.contains("장르 점수 3"));
        assertFalse(text.contains(anchor.getTitle()));
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        var context = history.prepare(user, room, "SF 영화 추천").context();
        assertEquals(question, context.get(0).text());
        assertFalse(context.get(1).text().contains("추천 이유(답변 시점)"));
        assertFalse(context.get(1).text().contains("개인화 기준(답변 시점)"));
        assertFalse(context.get(1).text().contains("내 반응(답변 시점)"));
        assertEquals(404, assertThrows(ChatException.class,
                () -> service.reply(Long.MAX_VALUE, room, question)).status());
        interactions.deleteById(event.getId());
        var next = service.reply(user, room, "내 취향에 맞는 영화 추천");
        assertTrue(next.messages().get(1).text().contains("기록이 부족"));
        assertEquals(0, interactions.count());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
    }
}
