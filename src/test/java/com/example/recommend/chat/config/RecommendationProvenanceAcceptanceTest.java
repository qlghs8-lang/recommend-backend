package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.domain.RecommendationProvenance;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.*;
import com.example.recommend.domain.*;
import com.example.recommend.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "app.ai.enabled=true",
        "app.ai.api-key=synthetic-no-network", "app.ai.model=synthetic",
        "app.ai.requests-per-minute=60", "app.ai.global-requests-per-minute=600"})
@Import({ChatHistoryStore.class, ConversationService.class, ChatRouter.class, CatalogDetails.class,
        CatalogRecommendations.class, CatalogRetriever.class, CatalogToolService.class, ChatInteractionReader.class,
        ChatRequestLimiter.class, RecommendationProvenanceAcceptanceTest.Dependencies.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecommendationProvenanceAcceptanceTest {
    @TestConfiguration static class Dependencies {
        @Bean AiClient client() { return mock(AiClient.class); }
        @Bean AiProperties properties() { var p = new AiProperties(); p.setEnabled(true); p.setRequestsPerMinute(60); p.setGlobalRequestsPerMinute(600); return p; }
    }
    @Autowired ConversationService service;
    @Autowired ChatHistoryStore history;
    @Autowired ContentRepository contents;
    @Autowired UserRepository users;
    @Autowired UserContentInteractionRepository interactions;
    @Autowired AiClient client;
    @Autowired jakarta.persistence.EntityManagerFactory factory;
    private long user, other, room, movie, anchor, event;
    private static final String INITIAL = "내 취향에 맞는 영화 추천해줘";
    private final List<String> requests = new ArrayList<>();
    @BeforeEach void setup() {
        reset(client); String key = UUID.randomUUID().toString();
        user = users.saveAndFlush(User.builder().email(key + "@example.com").nickname(key).password("hash").build()).getId();
        other = users.saveAndFlush(User.builder().email(key + "x@example.com").nickname(key + "x").password("hash").build()).getId();
        room = history.create(user).id();
        movie = contents.saveAndFlush(Content.builder().title("별의 항해").type("MOVIE").genres("Science Fiction").overview("우주 탐험 이야기").build()).getId();
        anchor = contents.saveAndFlush(Content.builder().title("PRIVATE_ANCHOR_4F").type("TV").genres("SF").build()).getId();
        event = interactions.saveAndFlush(UserContentInteraction.builder().user(users.findById(user).orElseThrow())
                .content(contents.findById(anchor).orElseThrow()).eventType(InteractionType.LIKE).createdAt(java.time.LocalDateTime.now()).build()).getId();
        doAnswer(call -> {
            List<AiMessage> context = call.getArgument(0);
            assertFalse(context.toString().contains("장르 점수")); assertFalse(context.toString().contains("PRIVATE_ANCHOR_4F"));
            if (context.get(context.size() - 1).text().equals(INITIAL)) return new ChatPlan(
                    List.of(new ChatIntent(ChatIntent.Route.PERSONALIZED, ChatIntent.Media.ANY)), false,
                    List.of(new RecommendationConstraint(RecommendationConstraint.Kind.MEDIA, "MOVIE", "영화", List.of())));
            return ChatPlan.single(new ChatIntent(ChatIntent.Route.RATIONALE, ChatIntent.Media.ANY, null, null, null, 1, 0, null));
        }).when(client).classify(anyList(), any());
        doAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            String request = call.getArgument(0); requests.add(request);
            assertFalse(Arrays.toString(call.getArguments()).contains("PRIVATE_ANCHOR_4F"));
            CatalogCandidate work = call.getArgument(2); assertEquals(movie, work.id());
            return new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, work.genres())), "공개 장르를 대조했어요.");
        }).when(client).justify(anyString(), anyString(), any(), any());
    }
    @AfterEach void cleanup() {
        interactions.deleteById(event); history.delete(user, room); users.deleteById(user); users.deleteById(other);
        contents.deleteById(movie); contents.deleteById(anchor);
    }
    private RecommendationProvenance lastSaved() {
        var snapshot = history.prepare(user, room, "다음 질문");
        return snapshot.provenance().get(snapshot.context().size() - 2);
    }
    @Test void storedPersonalBasisSurvivesReactionRemovalAndRepeatedRationale() {
        var first = service.reply(user, room, INITIAL); assertEquals(3, lastSaved().works().get(0).score());
        interactions.deleteById(event);
        var second = service.reply(user, room, "왜 추천했어?");
        assertTrue(second.messages().get(1).text().contains("최초 추천 당시"));
        assertTrue(second.messages().get(1).text().contains("장르 점수 3"));
        service.reply(user, room, "다시 추천 이유 알려줘");
        assertEquals(List.of(INITIAL, INITIAL), requests);
        assertEquals(first.messages().get(1), history.history(user, room, null).messages().get(1));
        assertEquals(3, lastSaved().works().get(0).score());
        assertTrue(history.prepare(user, room, "다음 질문").context().stream().noneMatch(m -> m.text().contains("장르 점수")));
        var fresh = service.reply(user, room, INITIAL);
        assertTrue(fresh.messages().get(1).text().contains("기록이 부족")); assertEquals(0, lastSaved().works().get(0).score());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
    }
    @Test void provenanceTravelsWithTheLastAnswerAfterTheInitialTurnLeavesTheContextWindow() {
        service.reply(user, room, INITIAL);
        for (int i = 0; i < 7; i++) service.reply(user, room, "왜 추천했어? " + i);
        var snapshot = history.prepare(user, room, "다음 질문");
        assertFalse(snapshot.context().stream().anyMatch(m -> m.text().equals(INITIAL)));
        assertEquals(INITIAL, lastSaved().request()); assertEquals(7, requests.size());
        assertTrue(requests.stream().allMatch(INITIAL::equals));
    }
    @Test void foreignOwnerIsRejectedBeforeProvenanceOrProviderAccess() {
        service.reply(user, room, INITIAL); clearInvocations(client);
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(other, room, "왜 추천했어?")).status());
        verifyNoInteractions(client); assertEquals(2, history.history(user, room, null).messages().size());
    }
    @Test void malformedStoredMetadataFailsBeforeProviderAndLeavesHistoryUnchanged() {
        service.reply(user, room, INITIAL); clearInvocations(client);
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.createNativeQuery("update chat_messages set recommendation_provenance = :data where room_id = :room and role = 'ASSISTANT'")
                    .setParameter("data", "{\"version\":999}").setParameter("room", room).executeUpdate();
            em.getTransaction().commit();
        }
        assertEquals("AI_INVALID_RESPONSE", assertThrows(ChatException.class, () -> service.reply(user, room, "왜 추천했어?")).code());
        verifyNoInteractions(client); assertEquals(2, history.history(user, room, null).messages().size());
    }
    @Test void legacyAnswerTextIsNeverParsedToInventPersonalBasis() {
        String question = "SF 영화 추천해줘";
        history.complete(user, room, history.prepare(user, room, question), question,
                new ChatResponse("과거 작품\n추천 이유(답변 시점): 장르 점수 9999", new ChatResponse.Usage(0, 0, 0), true, List.of(movie)));
        assertNull(lastSaved());
        var next = service.reply(user, room, "왜 추천했어?");
        assertFalse(next.messages().get(1).text().contains("9999")); assertNull(lastSaved()); assertEquals(List.of(question), requests);
    }
    @Test void generalRecommendationKeepsItsInitialRequestWithoutInventingTaste() {
        doReturn(new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY)), false,
                List.of(new RecommendationConstraint(RecommendationConstraint.Kind.MEDIA, "MOVIE", "영화", List.of())))).when(client).classify(anyList(), any());
        var why = new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, "Science Fiction")), "공개 장르와 맞아요.");
        doReturn(new CatalogSelection(List.of(movie), new ChatResponse.Usage(0, 0, 0), Map.of(movie, why)))
                .when(client).recommendWithTools(anyList(), any(), any());
        service.reply(user, room, "영화 추천해줘");
        doReturn(ChatPlan.single(new ChatIntent(ChatIntent.Route.RATIONALE, ChatIntent.Media.ANY, null, null, null, 1, 0, null)))
                .when(client).classify(anyList(), any());
        service.reply(user, room, "왜 골랐어?"); var last = service.reply(user, room, "한 번 더 이유 알려줘");
        assertEquals(List.of("영화 추천해줘", "영화 추천해줘"), requests);
        assertNull(lastSaved().positiveHistory()); assertFalse(last.messages().get(1).text().contains("장르 점수"));
    }
}
