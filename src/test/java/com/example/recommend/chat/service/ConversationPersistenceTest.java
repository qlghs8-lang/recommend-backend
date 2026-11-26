package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.config.AiProperties;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.dto.ConversationDtos.Room;
import com.example.recommend.chat.repository.*;
import com.example.recommend.domain.User;
import com.example.recommend.repository.UserRepository;
import com.example.recommend.service.RelatedDataDeletion;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "app.ai.enabled=true"})
@Import({ChatHistoryStore.class, ConversationService.class, ChatRequestLimiter.class,
        RelatedDataDeletion.class, ConversationPersistenceTest.Dependencies.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConversationPersistenceTest {
    @TestConfiguration static class Dependencies {
        @Bean ChatRouter client() { return mock(ChatRouter.class); }
        @Bean AiProperties properties() {
            var p = new AiProperties(); p.setEnabled(true); p.setRequestsPerMinute(60); return p;
        }
    }
    @Autowired ChatHistoryStore history;
    @Autowired ConversationService service;
    @Autowired ChatRoomRepository rooms;
    @Autowired ChatMessageRepository messages;
    @Autowired UserRepository users;
    @Autowired ChatRouter client;
    @Autowired RelatedDataDeletion deletion;
    @Autowired PlatformTransactionManager transactions;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagers;
    long owner, other;
    Room room;
    private ChatResponse response(String text) {
        return new ChatResponse(text, new ChatResponse.Usage(10, 2, 12), false);
    }
    @BeforeEach void setup() {
        reset(client);
        String suffix = UUID.randomUUID().toString();
        owner = users.saveAndFlush(User.builder().email(suffix + "@example.com").nickname(suffix).password("hash").build()).getId();
        other = users.saveAndFlush(User.builder().email(suffix + "x@example.com").nickname(suffix + "x").password("hash").build()).getId();
        room = history.create(owner);
    }
    @AfterEach void cleanup() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            deletion.forUser(owner); deletion.forUser(other);
            users.deleteById(owner); users.deleteById(other);
        });
    }
    private void append(long roomId, String question, String answer) {
        history.complete(owner, roomId, history.prepare(owner, roomId, question), question, response(answer));
    }

    private <T> T webRequest(java.util.concurrent.Callable<T> action) throws Exception {
        var em = entityManagers.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagers, new org.springframework.orm.jpa.EntityManagerHolder(em));
        try { return action.call(); }
        finally { TransactionSynchronizationManager.unbindResource(entityManagers); em.close(); }
    }

    @Test void webRequestsCanSendReloadAndSwitchToANewRoom() throws Exception {
        String longAnswer = "긴 답변 🎬 ".repeat(500);
        when(client.reply(eq(owner), anyList())).thenReturn(response(longAnswer));
        webRequest(() -> service.reply(owner, room.id(), "first question"));
        webRequest(() -> service.reply(owner, room.id(), "follow-up"));
        var second = webRequest(() -> history.create(owner));
        webRequest(() -> service.reply(owner, second.id(), "new question"));
        assertEquals(4, webRequest(() -> history.history(owner, room.id(), null)).room().messageCount());
        assertEquals(2, webRequest(() -> history.history(owner, second.id(), null)).room().messageCount());
        assertEquals(longAnswer, webRequest(() -> history.history(owner, second.id(), null)).messages().get(1).text());
    }

    @Test void persistsCompleteTurnAndReloadsWithoutATransactionDuringGeneration() {
        when(client.reply(eq(owner), anyList())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return response("안녕하세요");
        });
        var turn = service.reply(owner, room.id(), " 안녕 ");
        assertEquals(2, turn.messages().size());
        assertNotNull(turn.messages().get(0).id());
        assertEquals("안녕", turn.room().title());
        var restored = history.history(owner, room.id(), null);
        assertEquals(turn.messages(), restored.messages());
        assertEquals(2, restored.room().messageCount());
        verify(client).reply(eq(owner), eq(List.of(new AiMessage(AiMessage.Role.USER, "안녕"))));
        history.delete(owner, room.id());
        assertTrue(messages.findByRoomIdAndIdLessThanOrderByIdDesc(room.id(), Long.MAX_VALUE,
                org.springframework.data.domain.PageRequest.of(0, 1)).isEmpty());
    }

    @Test void allRoomOperationsRejectOtherUsersAndMissingRoomsWithoutProviderCalls() {
        append(room.id(), "private", "private answer");
        for (long id : new long[]{room.id(), Long.MAX_VALUE}) {
            assertEquals(404, assertThrows(ChatException.class, () -> history.history(other, id, null)).status());
            assertEquals(404, assertThrows(ChatException.class, () -> service.reply(other, id, "hello")).status());
            assertEquals(404, assertThrows(ChatException.class, () -> history.delete(other, id)).status());
        }
        assertTrue(history.list(other, null).rooms().isEmpty());
        assertEquals(2, history.history(owner, room.id(), null).messages().size());
        verifyNoInteractions(client);
    }

    @Test void providerFailureStoresNeitherQuestionNorAnswer() {
        when(client.reply(eq(owner), anyList())).thenThrow(ChatException.timeout());
        assertEquals(504, assertThrows(ChatException.class, () -> service.reply(owner, room.id(), "question")).status());
        assertTrue(history.history(owner, room.id(), null).messages().isEmpty());
        assertEquals(0, history.history(owner, room.id(), null).room().messageCount());
    }

    @Test void contextKeepsOnlyRecentCompleteTurnsFromTheSelectedRoom() {
        for (int i = 0; i < 8; i++) append(room.id(), "q" + i, "a" + i);
        var second = history.create(owner);
        append(second.id(), "unrelated secret", "unrelated answer");
        var context = history.prepare(owner, room.id(), "follow-up").context();
        assertEquals(13, context.size());
        assertEquals(new AiMessage(AiMessage.Role.USER, "q2"), context.get(0));
        assertEquals(new AiMessage(AiMessage.Role.ASSISTANT, "a7"), context.get(11));
        assertEquals("follow-up", context.get(12).text());
        assertTrue(context.stream().noneMatch(m -> m.text().contains("unrelated")));
    }

    @Test void reactionSnapshotsRemainVisibleWhileProviderContextExcludesOnlyAssistantSnapshotLines() {
        String snapshot = "내 반응(답변 시점): 좋아요 미설정 · 찜 설정 · 싫어요 미설정";
        String question = "내 찜 상태 확인\n" + snapshot;
        String answer = "First (ID: 1)\n영화\n" + snapshot + "\n카탈로그 줄거리: First overview"
                + "\n\nSecond (ID: 2)\n시리즈\n" + snapshot;
        append(room.id(), question, answer);
        assertEquals(answer, history.history(owner, room.id(), null).messages().get(1).text());
        var context = history.prepare(owner, room.id(), "next question").context();
        assertEquals(question, context.get(0).text());
        assertTrue(context.get(1).text().contains("First (ID: 1)\n영화\n카탈로그 줄거리: First overview"));
        assertTrue(context.get(1).text().contains("Second (ID: 2)\n시리즈"));
        assertFalse(context.get(1).text().contains(snapshot));
    }

    @Test void characterBudgetDropsWholeTurnsWithoutTruncatingOrRemovingCurrentQuestion() {
        append(room.id(), "old", "answer");
        append(room.id(), "latest", "a".repeat(11990));
        var context = history.prepare(owner, room.id(), "follow-up").context();
        assertEquals(List.of(new AiMessage(AiMessage.Role.USER, "follow-up")), context);
    }

    @Test void staleConcurrentCompletionIsRejectedAndDeletedRoomCannotBeResurrected() {
        var first = history.prepare(owner, room.id(), "first");
        var stale = history.prepare(owner, room.id(), "second");
        history.complete(owner, room.id(), first, "first", response("answer"));
        assertEquals(409, assertThrows(ChatException.class,
                () -> history.complete(owner, room.id(), stale, "second", response("stale answer"))).status());
        assertEquals(2, history.history(owner, room.id(), null).messages().size());
        var snapshot = history.prepare(owner, room.id(), "deleted");
        history.delete(owner, room.id());
        assertEquals(404, assertThrows(ChatException.class,
                () -> history.complete(owner, room.id(), snapshot, "deleted", response("late"))).status());
    }

    @Test void concurrentTransactionsSaveExactlyOneTurn() throws Exception {
        var snapshot = history.prepare(owner, room.id(), "question");
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Integer> save = () -> {
            assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try {
                history.complete(owner, room.id(), snapshot, "question", response("answer"));
                return 200;
            } catch (ChatException error) { return error.status(); }
        };
        try {
            var first = executor.submit(save); var second = executor.submit(save); start.countDown();
            var outcomes = new ArrayList<>(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS)));
            Collections.sort(outcomes);
            assertEquals(List.of(200, 409), outcomes);
            assertEquals(2, history.history(owner, room.id(), null).room().messageCount());
            assertEquals(2, history.history(owner, room.id(), null).messages().size());
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test void storageCapsRejectWorkBeforeCallingProvider() {
        for (int i = 1; i < 100; i++) history.create(owner);
        assertEquals(409, assertThrows(ChatException.class, () -> history.create(owner)).status());
        for (int i = 0; i < 100; i++) append(room.id(), "q", "a");
        assertEquals(409, assertThrows(ChatException.class, () -> service.reply(owner, room.id(), "extra")).status());
        assertEquals(200, history.history(owner, room.id(), null).room().messageCount());
        verifyNoInteractions(client);
    }

    @Test void cursorPagesHaveNoGapsAndAccountDeletionCleansUpChat() {
        for (int i = 0; i < 22; i++) append(room.id(), "q" + i, "a" + i);
        var latest = history.history(owner, room.id(), null);
        assertEquals(40, latest.messages().size()); assertTrue(latest.hasMore());
        var older = history.history(owner, room.id(), latest.nextBefore());
        assertEquals(4, older.messages().size()); assertFalse(older.hasMore());
        assertTrue(older.messages().get(3).id() < latest.messages().get(0).id());
        for (int i = 0; i < 21; i++) history.create(owner);
        var page = history.list(owner, null);
        assertEquals(20, page.rooms().size()); assertTrue(page.hasMore());
        assertEquals(2, history.list(owner, page.nextBefore()).rooms().size());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            deletion.forUser(owner); users.deleteById(owner); users.flush();
        });
        assertFalse(rooms.existsById(room.id()));
        // Recreate an empty fixture account so the common cleanup remains valid.
        owner = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.com")
                .nickname(UUID.randomUUID().toString()).password("hash").build()).getId();
    }
}

