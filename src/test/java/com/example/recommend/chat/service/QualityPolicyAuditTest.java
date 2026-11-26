package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.config.AiProperties;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.dto.ConversationDtos.Turn;
import com.example.recommend.domain.*;
import com.example.recommend.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Characterizes observed alternatives, including deficiencies; PASS is not a quality acceptance score. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "app.ai.enabled=true"})
@Import({ChatHistoryStore.class, ConversationService.class, ChatRouter.class, CatalogDetails.class,
        CatalogRecommendations.class, CatalogRetriever.class, CatalogToolService.class, ChatInteractionReader.class,
        ChatRequestLimiter.class, QualityPolicyAuditTest.Dependencies.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QualityPolicyAuditTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CORPUS = "/chat/conversation-quality-v1.json";
    private static final String REPLAYS = "/chat/quality-policy-audit-v1.json";
    private static final String CORPUS_HASH = "44f9cb9533dfb6bf6fe74a16069a1bceedfcc67f305b6a29e9a71c77467b1926";
    private static final Map<String, Object> RESULTS = new TreeMap<>();
    private static final ChatResponse.Usage ZERO = new ChatResponse.Usage(0, 0, 0);

    @TestConfiguration static class Dependencies {
        @Bean AiClient client() { return mock(AiClient.class); }
        @Bean AiProperties properties() {
            var properties = new AiProperties(); properties.setEnabled(true); return properties;
        }
    }
    @Autowired ConversationService service;
    @Autowired ChatHistoryStore history;
    @Autowired ContentRepository contents;
    @Autowired UserRepository users;
    @Autowired UserContentInteractionRepository interactions;
    @Autowired AiClient client;
    private long user, room;
    private final List<Long> created = new ArrayList<>();

    @BeforeAll static void clearReport() { RESULTS.clear(); }
    @BeforeEach void setup() {
        reset(client);
        String key = UUID.randomUUID().toString();
        user = users.saveAndFlush(User.builder().email(key + "@example.com").nickname(key).password("hash").build()).getId();
        room = history.create(user).id();
    }
    @AfterEach void cleanup() {
        history.delete(user, room);
        users.deleteById(user);
        contents.deleteAllById(created);
    }
    @AfterAll static void writeReport() throws Exception {
        var output = Path.of("target", "quality-policy-audit", "service-replay.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "evaluation", "recorded-synthetic-plans-and-oracles-through-H2-services",
                "notAModelQualityScore", true, "paidProviderCalls", 0, "expectedScenarios", 14,
                "completedScenarios", RESULTS.size(), "sourceCorpusSha256", CORPUS_HASH,
                "sourceRun", resource(REPLAYS).get("sourceRun"), "results", RESULTS)), StandardCharsets.UTF_8);
        assertEquals(14, RESULTS.size(), "Incomplete service audit; inspect Surefire before using the report");
    }

    @Test void recordedPlansRemainSeparateFromTheUnmodifiedOriginalOracle() throws Exception {
        var corpusBytes = bytes(CORPUS);
        assertEquals(CORPUS_HASH, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(corpusBytes)));
        var samples = resource(REPLAYS);
        assertEquals(CORPUS_HASH, samples.get("sourceCorpusSha256").asString());
        assertEquals(3, samples.get("cases").size());
        for (var sample : samples.get("cases")) {
            String id = sample.get("id").asString();
            var original = row(CORPUS, id);
            assertEquals(original.get("question"), sample.get("question"));
            assertTrue(original.get("history").isEmpty());
            var trace = sample.get("providerTrace").get(0);
            assertEquals("completed", trace.get("status").asString());
            var recorded = ClassifierPlan.parseProvider(trace.get("syntheticOutput").asString(), context(id));
            assertEquals(ChatPlan.parse(sample.get("recordedActualPlan").toString()), recorded);
            assertNotEquals(plan(id, true), recorded);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void vagueSeriesDurationEitherAsksForALocatorOrRecommendsWithoutDurationEnforcement(boolean oracle) throws Exception {
        long longEpisode = work("긴 회차 시리즈", "TV", 90);
        long shortEpisode = work("짧은 회차 시리즈", "TV", 10);
        long unknown = work("길이 미등록 시리즈", "TV", null);
        work("짧은 영화", "MOVIE", 5);
        var plan = plan("rec-tv", oracle);
        usePlan(plan);
        if (oracle) when(client.recommendWithTools(anyList(), any(), any())).thenAnswer(call -> {
            var candidates = ((CatalogTools) call.getArgument(1)).candidates();
            assertEquals(Set.of(longEpisode, shortEpisode, unknown), new HashSet<>(candidates.stream().map(CatalogCandidate::id).toList()));
            // Deliberately choose a long episode: this is permitted by the original plan, not a recommendation of good quality.
            return selection(longEpisode);
        });
        var turn = reply("rec-tv", oracle ? List.of(longEpisode) : List.of());
        String answer = turn.messages().get(1).text();
        assertEquals(oracle, turn.catalogGrounded());
        if (oracle) {
            assertTrue(answer.contains("회당 러닝타임(대표값): 90분"));
            verify(client).recommendWithTools(anyList(), any(), any());
        } else {
            assertTrue(answer.contains("제목·카탈로그 ID"));
            verify(client, never()).recommendWithTools(anyList(), any(), any());
        }
        record("rec-tv/" + variant(oracle), "rec-tv", plan, turn,
                oracle ? List.of(longEpisode, shortEpisode, unknown) : List.of());
    }

    static Stream<Arguments> titleCases() {
        return Stream.of("neither-title", "short-title-only", "full-title-only", "both-titles")
                .flatMap(state -> Stream.of(false, true).map(oracle -> Arguments.of(state, oracle)));
    }

    @ParameterizedTest @MethodSource("titleCases")
    void missingTitleBoundaryDependsOnCatalogContents(String state, boolean oracle) throws Exception {
        Long shortId = null, fullId = null;
        if (state.equals("short-title-only") || state.equals("both-titles")) shortId = work("XYZ", "MOVIE", 70);
        if (state.equals("full-title-only") || state.equals("both-titles")) fullId = work("없는 작품 XYZ", "MOVIE", 100);
        var plan = plan("detail-missing", oracle);
        usePlan(plan);
        Long chosen = oracle ? fullId : shortId;
        var ids = chosen == null ? List.<Long>of() : List.of(chosen);
        var turn = reply("detail-missing", ids);
        String answer = turn.messages().get(1).text();
        assertEquals(chosen != null, turn.catalogGrounded());
        if (chosen == null) assertTrue(answer.contains("현재 카탈로그에서 찾지 못했어요"));
        else {
            assertTrue(answer.contains((oracle ? "없는 작품 XYZ" : "XYZ") + " (ID: " + chosen + ")"));
            Long other = oracle ? shortId : fullId;
            if (other != null) assertFalse(answer.contains("ID: " + other + ")"));
        }
        verify(client, never()).recommendWithTools(anyList(), any(), any());
        record("detail-missing/" + state + "/" + variant(oracle), "detail-missing", plan, turn, List.of());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void qualitativeWinnerGetsNoInventedWinnerButNeitherTemplateExplainsTheRequestedBoundary(boolean oracle) throws Exception {
        work("달빛 항해", "MOVIE", 90);
        work("숲의 기록", "MOVIE", 100);
        var plan = plan("clarify-ranking", oracle);
        usePlan(plan);
        var turn = reply("clarify-ranking", List.of());
        String answer = turn.messages().get(1).text();
        assertFalse(turn.catalogGrounded());
        assertFalse(answer.contains("카탈로그 줄거리:"));
        assertTrue(answer.contains(oracle ? "제목·카탈로그 ID" : "실시간 뉴스·순위"));
        verify(client, never()).recommendWithTools(anyList(), any(), any());
        record("clarify-ranking/" + variant(oracle), "clarify-ranking", plan, turn, List.of());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitEpisodeLimitAndWholeSeriesTotalAlreadyHaveDifferentServicePolicies(boolean total) throws Exception {
        long shortEpisode = work("짧은 회차 시리즈", "TV", 10);
        work("긴 회차 시리즈", "TV", 90);
        work("길이 미등록 시리즈", "TV", null);
        String question = total ? "전체 시청 시간이 20분 이하인 시리즈 추천" : "회당 20분 이하인 시리즈 추천";
        String condition = total
                ? "{\"kind\":\"UNVERIFIABLE\",\"value\":\"series total\",\"source\":\"전체 시청 시간이 20분 이하\",\"terms\":[]}"
                : "{\"kind\":\"EPISODE_MINUTES_MAX\",\"value\":\"20\",\"source\":\"회당 20분 이하\",\"terms\":[]}";
        String wire = "{\"composition\":{\"requests\":[{\"route\":\"RECOMMEND\",\"media\":\"TV\",\"mediaSource\":\"시리즈\"}],\"comparison\":false,\"splitAfter\":null},\"constraints\":[" + condition + "]}";
        var plan = ClassifierPlan.parseProvider(wire, List.of(new AiMessage(AiMessage.Role.USER, question)));
        usePlan(plan);
        if (!total) when(client.recommendWithTools(anyList(), any(), any())).thenAnswer(call -> {
            assertEquals(List.of(shortEpisode), ((CatalogTools) call.getArgument(1)).candidates().stream().map(CatalogCandidate::id).toList());
            return selection(shortEpisode);
        });
        var turn = replyQuestion(question, total ? List.of() : List.of(shortEpisode));
        assertEquals(!total, turn.catalogGrounded());
        assertTrue(turn.messages().get(1).text().contains(total ? "확인하거나 보장할 수 없는 조건" : "회당 러닝타임(대표값): 10분"));
        if (total) verify(client, never()).recommendWithTools(anyList(), any(), any());
        record("policy-control/" + (total ? "series-total" : "episode-limit"), "explicit-policy-control", plan, turn,
                total ? List.of() : List.of(shortEpisode));
    }

    private long work(String title, String type, Integer minutes) {
        long id = contents.saveAndFlush(Content.builder().title(title).type(type).genres("Drama")
                .overview("짧게 볼 만한 이야기: " + title).runtimeMinutes(type.equals("MOVIE") ? minutes : null)
                .episodeRuntimeMinutes(type.equals("TV") ? minutes : null).build()).getId();
        created.add(id); return id;
    }
    private static CatalogSelection selection(long id) {
        return new CatalogSelection(List.of(id), ZERO, Map.of(id, new CatalogRationale(List.of(), "근거가 부족해요.")));
    }
    private void usePlan(ChatPlan plan) { when(client.classify(anyList(), any())).thenReturn(plan); }
    private Turn reply(String id, List<Long> ids) throws Exception { return replyQuestion(row(CORPUS, id).get("question").asString(), ids); }
    private Turn replyQuestion(String question, List<Long> ids) {
        assertEquals(404, assertThrows(ChatException.class, () -> service.reply(Long.MAX_VALUE, room, question)).status());
        verifyNoInteractions(client);
        long reactions = interactions.count();
        var turn = service.reply(user, room, question);
        assertEquals(2, turn.messages().size());
        assertEquals(question, turn.messages().get(0).text());
        assertEquals(turn.messages(), history.history(user, room, null).messages());
        assertEquals(ids, history.prepare(user, room, "다음 질문").context().get(1).contentIds());
        assertEquals(reactions, interactions.count());
        verify(client).classify(anyList(), any());
        verify(client, never()).explain(anyString(), any());
        return turn;
    }
    private void record(String key, String id, ChatPlan plan, Turn turn, List<Long> candidates) {
        RESULTS.put(key, Map.of("caseId", id, "plan", plan, "catalogGrounded", turn.catalogGrounded(),
                "answer", turn.messages().get(1).text(), "observedCandidateIds", candidates,
                "persistedPublicReferenceIds", history.prepare(user, room, "다음 질문").context().get(1).contentIds(),
                "ownershipAndPersistenceVerified", true));
    }
    private static String variant(boolean oracle) { return oracle ? "original-oracle" : "recorded-model"; }
    private static ChatPlan plan(String id, boolean oracle) throws Exception {
        return oracle ? ChatPlan.parse(row(CORPUS, id).get("expected").toString())
                : ClassifierPlan.parseProvider(row(REPLAYS, id).get("providerTrace").get(0).get("syntheticOutput").asString(), context(id));
    }
    private static List<AiMessage> context(String id) throws Exception {
        return List.of(new AiMessage(AiMessage.Role.USER, row(CORPUS, id).get("question").asString()));
    }
    private static JsonNode row(String path, String id) throws Exception {
        for (var row : resource(path).get("cases")) if (row.get("id").asString().equals(id)) return row;
        throw new IllegalArgumentException("Missing fixed synthetic case: " + id);
    }
    private static JsonNode resource(String path) throws Exception { return JSON.readTree(new String(bytes(path), StandardCharsets.UTF_8)); }
    private static byte[] bytes(String path) throws Exception {
        try (var stream = QualityPolicyAuditTest.class.getResourceAsStream(path)) {
            assertNotNull(stream); return stream.readAllBytes();
        }
    }
}
