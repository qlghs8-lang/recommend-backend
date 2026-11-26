package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.dto.ConversationDtos.Message;
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

/** Actual H2 services and stored history; only the model boundary is scripted unless explicitly opted in. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "app.ai.enabled=true",
        "app.ai.api-key=synthetic-no-network", "app.ai.model=synthetic", "app.ai.max-output-tokens=800",
        "app.ai.timeout-seconds=20", "app.ai.requests-per-minute=60", "app.ai.global-requests-per-minute=600"})
@Import({ChatHistoryStore.class, ConversationService.class, ChatRouter.class, CatalogDetails.class,
        CatalogRecommendations.class, CatalogRetriever.class, CatalogToolService.class, ChatInteractionReader.class,
        ChatRequestLimiter.class, IntegratedConversationAcceptanceTest.Dependencies.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IntegratedConversationAcceptanceTest {
    private static final ChatResponse.Usage ZERO = new ChatResponse.Usage(0, 0, 0);
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
    private long user, other;
    private final List<Long> rooms = new ArrayList<>();
    private final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeEach void setup() throws Exception {
        reset(client);
        String key = UUID.randomUUID().toString();
        user = users.saveAndFlush(User.builder().email(key + "@example.com").nickname(key).password("hash").build()).getId();
        other = users.saveAndFlush(User.builder().email(key + "-other@example.com").nickname(key + "-other").password("hash").build()).getId();
        for (var row : ConversationAcceptanceCorpus.root().get("works")) {
            var work = Content.builder().title(row.get("title").asString()).type(row.get("type").asString())
                    .genres(row.get("genres").asString()).overview(row.get("overview").asString())
                    .releaseDate(java.time.LocalDate.of(2020, 1, 2)).build();
            if (row.has("runtime")) work.setRuntimeMinutes(row.get("runtime").asInt());
            if (row.has("episodeRuntime")) work.setEpisodeRuntimeMinutes(row.get("episodeRuntime").asInt());
            ids.put(row.get("key").asString(), contents.saveAndFlush(work).getId());
        }
    }
    @AfterEach void cleanup() {
        for (long room : rooms) history.delete(user, room);
        users.deleteById(other); users.deleteById(user); contents.deleteAllById(ids.values());
    }
    private long room() { long room = history.create(user).id(); rooms.add(room); return room; }

    @Test void scriptedPlansTraverseThreePersistedConversationsThroughActualServices() throws Exception {
        var results = runStories(null, 1, null);
        assertEquals(14, results.size()); assertTrue(results.stream().allMatch(r -> "PASS".equals(r.get("status"))));
    }
    @Test void ownershipRejectsBeforeAnyProviderAndSavesNoMessages() {
        long room = room();
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(other, room, "안녕")).status());
        verifyNoInteractions(client); assertTrue(history.history(user, room, null).messages().isEmpty());
    }
    @Test void oldTurnEvidenceIsRejectedWithoutPartiallySavingTheNextTurn() {
        long room = room();
        doReturn(new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY)), false, List.of(),
                new Clarification(Clarification.Reason.QUALITATIVE_COMPARISON, "뛰어난 연출"))).when(client).classify(anyList(), any());
        service.reply(user, room, "달빛 항해와 숲의 기록 중 뛰어난 연출은?");
        var before = history.history(user, room, null).messages();
        assertEquals("AI_INVALID_RESPONSE", assertThrows(ChatException.class,
                () -> service.reply(user, room, "장르와 줄거리 기준으로 비교해줘")).code());
        assertEquals(before, history.history(user, room, null).messages());
        assertTrue(history.prepare(user, room, "다음 질문").context().get(1).contentIds().isEmpty());
        verify(client, never()).recommendWithTools(anyList(), any(), any());
        verify(client, never()).explain(anyString(), any());
    }
    @Test @EnabledIfSystemProperty(named = "chatAcceptanceServicesLive", matches = "true")
    void actualModelTraversesTheSameStoredConversationsTwice() throws Exception {
        var p = new AiProperties(); p.setEnabled(true);
        p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY")); p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        try (var trace = new SyntheticClassifierTrace(p, AiConfig.createSdk(p, "https://api.openai.com/v1"))) {
            var results = runStories(trace.client, 2, trace);
            assertEquals(28, results.size());
            assertTrue(trace.items.size() <= 36, "Fixed request bound exceeded");
            assertTrue(results.stream().allMatch(r -> "PASS".equals(r.get("status"))), "Service acceptance failed; inspect fixed synthetic report");
        }
    }

    private List<Map<String, Object>> runStories(AiClient delegate, int repeats, SyntheticClassifierTrace trace) throws Exception {
        var results = new ArrayList<Map<String, Object>>(); var started = Instant.now();
        boolean circuitOpen = false;
        try {
            for (int repetition = 1; repetition <= repeats; repetition++) for (var story : ConversationAcceptanceCorpus.root().get("stories")) {
                long room = room(); var persisted = new ArrayList<Message>(); boolean dependentFailure = false;
                for (var step : story.get("steps")) {
                    var row = new LinkedHashMap<String, Object>(); results.add(row);
                    row.put("story", story.get("id").asString()); row.put("step", step.get("id").asString());
                    row.put("repetition", repetition); row.put("status", "NOT_RUN");
                    if (circuitOpen || dependentFailure) continue;
                    String question = step.get("question").asString();
                    var context = history.prepare(user, room, question).context();
                    var expected = ConversationAcceptanceCorpus.expected(step, context);
                    var expectedIds = step.get("ids").valueStream().map(n -> ids.get(n.asString())).toList();
                    var plans = new ArrayList<ChatPlan>(); int[] generationCalls = {0};
                    doAnswer(call -> {
                        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                        assertEquals(context, call.getArgument(0));
                        var plan = delegate == null ? expected : delegate.classify(call.getArgument(0), call.getArgument(1));
                        plans.add(plan); return plan;
                    }).when(client).classify(anyList(), any());
                    doAnswer(call -> {
                        assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); generationCalls[0]++;
                        CatalogTools tools = call.getArgument(1);
                        row.put("check", "CANDIDATES");
                        row.put("candidateIds", tools.candidates().stream().map(CatalogCandidate::id).toList());
                        assertEquals(expectedIds, tools.candidates().stream().map(CatalogCandidate::id).toList());
                        if (delegate != null) return delegate.recommendWithTools(call.getArgument(0), tools, call.getArgument(2));
                        var work = tools.candidates().get(0);
                        return new CatalogSelection(List.of(work.id()), ZERO, Map.of(work.id(), new CatalogRationale(
                                List.of(new CatalogRationale.Evidence(CatalogRationale.Field.OVERVIEW, work.overview())), "카탈로그 조건에 맞는 작품이에요.")));
                    }).when(client).recommendWithTools(anyList(), any(), any());
                    doAnswer(call -> { throw new AssertionError("Unplanned explanation branch"); }).when(client).explain(anyString(), any());
                    long reactions = interactions.count(); int traceStart = trace == null ? 0 : trace.items.size();
                    try {
                        row.put("check", "SERVICE_REPLY");
                        var turn = service.reply(user, room, question);
                        row.put("answer", turn.messages().get(1).text()); row.put("usage", turn.usage());
                        row.put("actualGrounded", turn.catalogGrounded()); row.put("check", "PLAN");
                        assertEquals(1, plans.size());
                        var issues = ConversationAcceptanceCorpus.planIssues(expected, plans.get(0), step.get("optionalConstraints"));
                        row.put("issues", issues); assertEquals(List.of(), issues);
                        row.put("check", "ANSWER");
                        assertEquals(step.get("grounded").asBoolean(), turn.catalogGrounded());
                        assertEquals(2, turn.messages().size()); assertEquals(question, turn.messages().get(0).text());
                        String answer = turn.messages().get(1).text();
                        for (var text : step.get("contains")) assertTrue(answer.contains(text.asString()));
                        row.put("check", "PERSISTENCE_AND_REFERENCES");
                        persisted.addAll(turn.messages()); assertEquals(persisted, history.history(user, room, null).messages());
                        var after = history.prepare(user, room, "다음 질문").context();
                        row.put("publicIds", after.get(after.size() - 2).contentIds());
                        assertEquals(expectedIds, after.get(after.size() - 2).contentIds());
                        row.put("check", "NO_EXTRA_GENERATION_OR_MUTATION");
                        assertEquals(step.get("id").asString().equals("episode-limit") ? 1 : 0, generationCalls[0]);
                        assertEquals(reactions, interactions.count());
                        row.put("answer", answer); row.put("publicIds", expectedIds); row.put("usage", turn.usage());
                        row.put("status", "PASS");
                        row.remove("check");
                    } catch (ChatException e) {
                        row.put("status", e.code()); dependentFailure = true;
                        circuitOpen = e.status() == 429 || e.status() == 503 || e.status() == 504;
                    } catch (AssertionError e) { row.put("status", "ASSERTION_FAILED"); dependentFailure = true; }
                    catch (RuntimeException e) { row.put("status", "UNEXPECTED_TEST_ERROR"); row.put("errorType", e.getClass().getSimpleName()); dependentFailure = true; }
                    finally {
                        if (!plans.isEmpty()) row.put("actualPlan", plans.get(0));
                        row.put("generationCalls", generationCalls[0]);
                        if (trace != null) row.put("providerTrace", List.copyOf(trace.items.subList(traceStart, trace.items.size())));
                    }
                    if (delegate != null && !circuitOpen) Thread.sleep(1000);
                }
            }
        } finally {
            Files.createDirectories(Path.of("target/chat-quality"));
            var report = new LinkedHashMap<String, Object>();
            report.put("evaluation", delegate == null ? "scripted-model-real-H2-services-and-persistence" : "actual-model-real-H2-services-and-persistence");
            report.put("model", delegate == null ? "scripted" : System.getenv("CHAT_EVAL_MODEL"));
            report.put("policySha256", ConversationAcceptanceCorpus.fingerprint()); report.put("repeats", repeats);
            report.put("plannedTurns", 14 * repeats); report.put("totalOutputTokens", 800); report.put("classificationMaxOutputTokens", 544);
            report.put("timeoutSeconds", 20); report.put("results", results);
            Files.writeString(Path.of("target/chat-quality/acceptance-services-" + (delegate == null ? "scripted-" : "live-")
                    + started.toString().replaceAll("[^0-9]", "") + ".json"), ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        return results;
    }
}
