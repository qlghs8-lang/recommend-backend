package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.*;
import com.example.recommend.domain.*;
import com.example.recommend.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real storage/SQL/router, synthetic accounts only. Model accuracy is measured only by explicit paid opt-in. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "app.ai.enabled=true",
        "app.ai.api-key=synthetic-no-network", "app.ai.model=synthetic",
        "app.ai.requests-per-minute=60", "app.ai.global-requests-per-minute=600"})
@Import({ChatHistoryStore.class, ConversationService.class, ChatRouter.class, CatalogDetails.class,
        CatalogRecommendations.class, CatalogRetriever.class, CatalogToolService.class, ChatInteractionReader.class,
        ChatRequestLimiter.class, PersonalizedRationaleAcceptanceTest.Dependencies.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PersonalizedRationaleAcceptanceTest {
    @TestConfiguration static class Dependencies {
        @Bean AiClient client() { return mock(AiClient.class); }
        @Bean AiProperties properties() { var p = new AiProperties(); p.setEnabled(true); return p; }
    }
    @Autowired ConversationService service;
    @Autowired ChatHistoryStore history;
    @Autowired ContentRepository contents;
    @Autowired UserRepository users;
    @Autowired UserContentInteractionRepository interactions;
    @Autowired AiClient client;
    private JsonNode fixture;
    private final Map<String, Long> ids = new LinkedHashMap<>();
    private final List<Long> events = new ArrayList<>();
    private long user, other, room, ownerEvent;

    @BeforeEach void setup() throws Exception {
        reset(client);
        try (var input = getClass().getResourceAsStream("/chat/personalized-rationale-v1.json")) {
            fixture = ConversationQualityCorpus.JSON.readTree(Objects.requireNonNull(input));
        }
        String key = UUID.randomUUID().toString();
        user = users.saveAndFlush(User.builder().email(key + "@example.com").nickname(key).password("hash").build()).getId();
        other = users.saveAndFlush(User.builder().email(key + "-other@example.com").nickname(key + "-other").password("hash").build()).getId();
        room = history.create(user).id();
        for (var row : fixture.get("works")) ids.put(row.get("key").asString(), contents.saveAndFlush(Content.builder()
                .title(row.get("title").asString()).type(row.get("type").asString()).genres(row.get("genres").asString())
                .overview(row.get("overview").asString()).rating(row.get("rating").asDouble()).build()).getId());
        ownerEvent = react(user, "ownerAnchor"); react(other, "foreignAnchor");
        scriptClassification(); scriptRationales();
    }
    @AfterEach void cleanup() {
        interactions.deleteAllById(events); history.delete(user, room);
        users.deleteById(other); users.deleteById(user); contents.deleteAllById(ids.values());
    }
    private long react(long owner, String work) {
        long id = interactions.saveAndFlush(UserContentInteraction.builder().user(users.findById(owner).orElseThrow())
                .content(contents.findById(ids.get(work)).orElseThrow()).eventType(InteractionType.LIKE)
                .createdAt(java.time.LocalDateTime.now()).build()).getId();
        events.add(id); return id;
    }
    private String q(String name) { return fixture.get("questions").get(name).asString(); }
    private List<Long> originalIds() { return List.of(ids.get("first"), ids.get("second"), ids.get("third")); }
    private ChatPlan plan(String question) {
        if (question.equals(q("initial"))) return new ChatPlan(
                List.of(new ChatIntent(ChatIntent.Route.PERSONALIZED, ChatIntent.Media.ANY)), false,
                List.of(new RecommendationConstraint(RecommendationConstraint.Kind.MEDIA, "MOVIE", "영화", List.of())));
        return ChatPlan.single(new ChatIntent(ChatIntent.Route.RATIONALE, ChatIntent.Media.ANY,
                null, null, null, 1, question.equals(q("ordinal")) ? 2 : question.equals(q("repeat")) ? 1 : 0, null));
    }
    private void scriptClassification() {
        doAnswer(call -> {
            List<AiMessage> context = call.getArgument(0); publicOnly(context);
            return plan(context.get(context.size() - 1).text());
        }).when(client).classify(anyList(), any());
    }
    private CatalogRationale rationale(CatalogCandidate work) {
        return new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, work.genres())),
                "공개 장르를 바탕으로 설명했어요.");
    }
    private void scriptRationales() {
        doAnswer(call -> {
            publicOnly(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            return rationale(call.getArgument(2));
        }).when(client).justify(anyString(), anyString(), any(), any());
        doAnswer(call -> {
            publicOnly(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            List<CatalogCandidate> works = call.getArgument(2); var result = new LinkedHashMap<Long, CatalogRationale>();
            works.forEach(w -> result.put(w.id(), rationale(w))); return result;
        }).when(client).justifyMany(anyString(), anyString(), anyList(), any());
    }
    private void publicOnly(Object... values) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        String text = Arrays.toString(values);
        for (String marker : List.of("PRIVATE_OWNER_ANCHOR_7C2", "PRIVATE_FOREIGN_ANCHOR_9D4",
                "개인화 기준(답변 시점)", "추천 이유(답변 시점)", "내 반응(답변 시점)", "장르 점수")) assertFalse(text.contains(marker), marker);
        for (Object value : values) if (value instanceof List<?> list) for (Object item : list)
            if (item instanceof AiMessage message) {
                assertFalse(message.contentIds().contains(ids.get("ownerAnchor")));
                assertFalse(message.contentIds().contains(ids.get("foreignAnchor")));
            }
    }
    private String initial() {
        var turn = service.reply(user, room, q("initial"));
        String text = turn.messages().get(1).text();
        assertTrue(text.contains("장르 점수 3"));
        assertEquals(originalIds(), history.prepare(user, room, q("group")).context().get(1).contentIds());
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
        return text;
    }

    @Test void groupRationaleAfterReactionRemovalKeepsStoredSnapshotButUsesOnlyPublicEvidence() {
        String original = initial(); interactions.deleteById(ownerEvent);
        var result = service.reply(user, room, q("group"));
        assertTrue(result.catalogGrounded());
        assertTrue(result.messages().get(1).text().contains("카탈로그 근거"));
        assertEquals(original, history.history(user, room, null).messages().get(1).text());
        assertEquals(originalIds(), history.prepare(user, room, q("repeat")).context().get(3).contentIds());
        assertEquals(List.of(events.get(1)), interactions.findAll().stream().map(UserContentInteraction::getId).toList());
        verify(client).justifyMany(eq(q("initial")), eq(q("group")), argThat(w -> w.stream().map(CatalogCandidate::id).toList().equals(originalIds())), any());
    }

    @Test void ordinalUsesOneTrustedWorkAndDoesNotReadForeignTaste() {
        initial(); var result = service.reply(user, room, q("ordinal"));
        assertTrue(result.catalogGrounded());
        assertEquals(List.of(ids.get("second")), history.prepare(user, room, q("repeat")).context().get(3).contentIds());
        verify(client).justify(eq(q("initial")), eq(q("ordinal")), argThat(w -> w.id() == ids.get("second")), any());
        verify(client, never()).justifyMany(anyString(), anyString(), anyList(), any());
        assertEquals(2, interactions.findAllById(events).size());
    }

    @Test void forgedPublicEvidenceDoesNotPersistAPartialFollowup() {
        initial(); var before = history.history(user, room, null).messages();
        doReturn(new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, "NOT_IN_CATALOG")), "잘못된 해석"))
                .when(client).justify(anyString(), anyString(), any(), any());
        assertEquals("AI_INVALID_RESPONSE", assertThrows(ChatException.class, () -> service.reply(user, room, q("ordinal"))).code());
        assertEquals(before, history.history(user, room, null).messages());
        assertEquals(2, interactions.findAllById(events).size());
    }

    @Test void catalogChangeDuringGenerationRejectsTheEntireFollowup() {
        initial(); var before = history.history(user, room, null).messages();
        doAnswer(call -> {
            CatalogCandidate work = call.getArgument(2); contents.deleteById(work.id()); return rationale(work);
        }).when(client).justify(anyString(), anyString(), any(), any());
        assertEquals("AI_INVALID_RESPONSE", assertThrows(ChatException.class, () -> service.reply(user, room, q("ordinal"))).code());
        // Cards resolve the current catalog; a deleted work disappears without changing stored messages.
        var expected = before.stream().map(message -> new com.example.recommend.chat.dto.ConversationDtos.Message(
                message.id(), message.role(), message.text(), message.createdAt(),
                message.contents().stream().filter(link -> !link.id().equals(ids.get("second"))).toList())).toList();
        assertEquals(expected, history.history(user, room, null).messages());
    }

    @Test void foreignAccountCannotReadOrExtendThePersonalizedRoom() {
        initial(); clearInvocations(client); var before = history.history(user, room, null).messages();
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(other, room, q("group"))).status());
        assertEquals(404, assertThrows(ChatException.class, () -> history.history(other, room, null)).status());
        verifyNoInteractions(client); assertEquals(before, history.history(user, room, null).messages());
    }

    @Test void freshRecommendationRecomputesOwnerTasteWithoutChangingOldAnswers() {
        String original = initial(); interactions.deleteById(ownerEvent);
        var result = service.reply(user, room, q("refresh"));
        assertTrue(result.messages().get(1).text().contains("기록이 부족"));
        assertEquals(List.of(ids.get("third"), ids.get("first"), ids.get("second")),
                history.prepare(user, room, q("group")).context().get(3).contentIds());
        assertEquals(original, history.history(user, room, null).messages().get(1).text());
        verify(client, never()).justify(anyString(), anyString(), any(), any());
        verify(client, never()).justifyMany(anyString(), anyString(), anyList(), any());
    }

    @Test void privateReactionListCannotBecomeARationaleReferenceAfterReload() {
        doReturn(ChatPlan.single(new ChatIntent(ChatIntent.Route.LIST_LIKE, ChatIntent.Media.ANY))).when(client).classify(anyList(), any());
        service.reply(user, room, "내 좋아요 목록 보여줘");
        assertTrue(history.history(user, room, null).messages().get(1).text().contains("PRIVATE_OWNER_ANCHOR_7C2"));
        scriptClassification(); service.reply(user, room, q("group"));
        assertFalse(history.history(user, room, null).messages().get(3).text().contains("PRIVATE_OWNER_ANCHOR_7C2"));
        assertTrue(history.prepare(user, room, q("repeat")).context().get(1).contentIds().isEmpty());
        verify(client, never()).justify(anyString(), anyString(), any(), any());
        verify(client, never()).justifyMany(anyString(), anyString(), anyList(), any());
    }

    @Test void auditsPersonalizedGroundsAndOriginalRequestSeparatelyFromContractChecks() throws Exception {
        audit(null, null);
    }

    @Test @EnabledIfSystemProperty(named = "chatPersonalizedRationaleLive", matches = "true")
    void actualModelRunsTheSameFourStoredTurnsOnce() throws Exception {
        var p = new AiProperties(); p.setEnabled(true); p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY"));
        p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        try (var trace = new SyntheticClassifierTrace(p, AiConfig.createSdk(p, "https://api.openai.com/v1"))) { audit(trace.client, trace); }
    }

    /** Audit gates are observations, never a passing quality assertion. Contract failures still fail JUnit. */
    private void audit(AiClient delegate, SyntheticClassifierTrace trace) throws Exception {
        var originalRequests = new ArrayList<String>(); var rows = new ArrayList<Map<String, Object>>();
        var report = new LinkedHashMap<String, Object>();
        report.put("fixture", "personalized-rationale-v1"); report.put("mode", delegate == null ? "scripted-boundary" : "actual-model");
        if (delegate != null) report.put("model", System.getenv("CHAT_EVAL_MODEL"));
        report.put("qualityGate", "NOT_RUN"); report.put("contractStatus", "NOT_RUN");
        doAnswer(call -> {
            List<AiMessage> context = call.getArgument(0); publicOnly(context);
            String current = context.get(context.size() - 1).text();
            assertEquals(history.prepare(user, room, current).context(), context);
            if (trace != null) assertTrue(trace.items.size() < fixture.get("liveBound").get("maxProviderCalls").asInt());
            ChatPlan actual = delegate == null ? plan(current) : delegate.classify(context, call.getArgument(1));
            ChatIntent expected = plan(current).requests().get(0);
            assertEquals(1, actual.requests().size()); assertEquals(expected.route(), actual.requests().get(0).route());
            if (expected.route() == ChatIntent.Route.RATIONALE) {
                assertEquals(expected.referenceTurn(), actual.requests().get(0).referenceTurn());
                assertEquals(expected.referencePosition(), actual.requests().get(0).referencePosition());
            } else assertTrue(actual.constraints().stream().anyMatch(c -> c.kind() == RecommendationConstraint.Kind.MEDIA && c.value().equals("MOVIE")));
            return actual;
        }).when(client).classify(anyList(), any());
        doAnswer(call -> {
            String original = call.getArgument(0); CatalogCandidate work = call.getArgument(2);
            publicOnly(original, call.getArgument(1), work); originalRequests.add(original);
            if (trace != null) assertTrue(trace.items.size() < 6);
            return delegate == null ? rationale(work) : delegate.justify(original, call.getArgument(1), work, call.getArgument(3));
        }).when(client).justify(anyString(), anyString(), any(), any());
        doAnswer(call -> {
            String original = call.getArgument(0); List<CatalogCandidate> works = call.getArgument(2);
            publicOnly(original, call.getArgument(1), works); originalRequests.add(original);
            if (trace != null) assertTrue(trace.items.size() < 6);
            if (delegate != null) return delegate.justifyMany(original, call.getArgument(1), works, call.getArgument(3));
            var result = new LinkedHashMap<Long, CatalogRationale>(); works.forEach(w -> result.put(w.id(), rationale(w))); return result;
        }).when(client).justifyMany(anyString(), anyString(), anyList(), any());
        try {
            String first = initial(); rows.add(Map.of("step", "initial", "status", "PASS"));
            interactions.deleteById(ownerEvent);
            String group = service.reply(user, room, q("group")).messages().get(1).text();
            assertEquals(originalIds(), history.prepare(user, room, q("repeat")).context().get(3).contentIds());
            rows.add(Map.of("step", "group-after-reaction-removal", "status", "PASS"));
            service.reply(user, room, q("repeat"));
            assertEquals(List.of(ids.get("first")), history.prepare(user, room, q("refresh")).context().get(5).contentIds());
            rows.add(Map.of("step", "repeated-rationale", "status", "PASS"));
            assertTrue(service.reply(user, room, q("refresh")).messages().get(1).text().contains("기록이 부족"));
            rows.add(Map.of("step", "fresh-taste", "status", "PASS"));
            assertEquals(8, history.history(user, room, null).messages().size());
            assertEquals(first, history.history(user, room, null).messages().get(1).text());
            assertEquals(1, interactions.findAllById(events).size());
            verify(client, never()).recommendWithTools(anyList(), any(), any());
            verify(client, never()).explain(anyString(), any());
            boolean grounds = group.contains("추천 이유(답변 시점)") && group.contains("장르 점수 3");
            boolean original = originalRequests.size() == 2 && originalRequests.stream().allMatch(q("initial")::equals);
            report.put("gates", Map.of("privateSnapshotsExcluded", "PASS", "personalizedGroundsRetained", grounds ? "PASS" : "FAIL",
                    "originalRequestRetained", original ? "PASS" : "FAIL"));
            report.put("rationaleOriginalRequests", originalRequests);
            report.put("qualityGate", grounds && original ? "PASS" : "FAIL"); report.put("contractStatus", "PASS");
        } catch (ChatException e) { report.put("contractStatus", e.code()); throw e;
        } catch (AssertionError e) { report.put("contractStatus", "EXPECTATION_MISMATCH"); throw e;
        } finally {
            report.put("turns", rows);
            if (trace != null) { report.put("providerCalls", trace.items.size()); report.put("responses", trace.items); }
            Path directory = Path.of("target/personalized-rationale"); Files.createDirectories(directory);
            Files.writeString(directory.resolve((delegate == null ? "scripted-" : "live-") + Instant.now().toString().replaceAll("[^0-9]", "") + ".json"),
                    ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
}
