package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.*;
import com.example.recommend.domain.User;
import com.example.recommend.repository.*;
import com.openai.core.RequestOptions;
import com.openai.models.responses.ResponseCreateParams;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import javax.sql.DataSource;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.example.recommend.chat.client.RecommendationConstraint.Kind.*;
import static com.example.recommend.chat.config.RuntimeRecommendationAcceptanceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Public source data only; accounts, conversations and writes stay in isolated H2. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "app.ai.enabled=true",
        "app.ai.api-key=synthetic-no-network", "app.ai.model=synthetic", "app.ai.max-output-tokens=800",
        "app.ai.timeout-seconds=20", "app.ai.requests-per-minute=60", "app.ai.global-requests-per-minute=600"})
@Import({ChatHistoryStore.class, ConversationService.class, ChatRouter.class, CatalogDetails.class,
        CatalogRecommendations.class, CatalogRetriever.class, CatalogToolService.class, ChatInteractionReader.class,
        ChatRequestLimiter.class, RuntimeModelAcceptanceTest.Dependencies.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RuntimeModelAcceptanceTest {
    static final Path DIRECTORY = Path.of("target/runtime-model-acceptance");
    static final ChatResponse.Usage ZERO = new ChatResponse.Usage(0, 0, 0);
    @TestConfiguration static class Dependencies {
        @Bean AiClient client() { return mock(AiClient.class); }
        @Bean AiProperties properties() {
            var p = new AiProperties(); p.setEnabled(true); p.setRequestsPerMinute(60); return p;
        }
    }
    @Autowired ConversationService service;
    @Autowired ChatHistoryStore history;
    @Autowired UserRepository users;
    @Autowired ContentRepository contents;
    @Autowired DataSource dataSource;
    @Autowired AiClient client;
    Long owner;
    final List<Long> rooms = new ArrayList<>(), copiedIds = new ArrayList<>();

    static byte[] corpus() throws Exception {
        try (var input = RuntimeModelAcceptanceTest.class.getResourceAsStream("/chat/runtime-model-v1.json")) {
            return Objects.requireNonNull(input).readAllBytes();
        }
    }
    static List<JsonNode> steps() throws Exception { return JSON.readTree(corpus()).get("steps").valueStream().toList(); }
    static boolean flag(JsonNode step, String name) { return step.has(name) && step.get(name).asBoolean(); }
    static List<RecommendationConstraint> expectedConditions(JsonNode step) {
        if (flag(step, "clarify")) return List.of();
        var result = new ArrayList<RecommendationConstraint>();
        boolean tv = step.get("media").asString().equals("TV");
        result.add(c(MEDIA, tv ? "TV" : "MOVIE", tv ? "시리즈" : "영화"));
        if (step.has("max")) result.add(c(tv ? EPISODE_MINUTES_MAX : MINUTES_MAX,
                "" + step.get("max").asInt(), step.get("source").asString()));
        if (flag(step, "reference")) result.add(c(REFERENCE, "ONLY_LAST", "그중"));
        if (flag(step, "shortest")) result.add(c(ORDER, "SHORTER_MOVIE", "가장 짧은"));
        if (flag(step, "unverifiable")) result.add(c(UNVERIFIABLE, "검증 불가", step.get("source").asString()));
        return result;
    }
    static ChatPlan expectedPlan(JsonNode step) {
        return flag(step, "clarify")
                ? new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY)), false, List.of(),
                    new Clarification(Clarification.Reason.TV_DURATION_UNIT, step.get("source").asString()))
                : new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY)), false, expectedConditions(step));
    }
    static void verifyPlan(JsonNode step, ChatPlan actual, List<AiMessage> context) {
        var expected = expectedPlan(step);
        assertEquals(expected.requests().stream().map(ChatIntent::route).toList(), actual.requests().stream().map(ChatIntent::route).toList());
        assertFalse(actual.comparison());
        // Compare semantic kinds/values, accepting any valid exact source span, never extra constraints.
        java.util.function.Function<RecommendationConstraint, String> key = c -> c.kind() + ":" + (c.kind() == UNVERIFIABLE ? "" : c.value());
        assertEquals(expected.constraints().stream().map(key).sorted().toList(), actual.constraints().stream().map(key).sorted().toList());
        String question = context.get(context.size() - 1).text();
        RecommendationCriteria.from(actual.constraints(), context, question);
        if (flag(step, "clarify")) {
            assertNotNull(actual.clarification()); assertEquals(Clarification.Reason.TV_DURATION_UNIT, actual.clarification().reason());
            actual.clarification().validate(question);
        } else assertNull(actual.clarification());
    }
    static List<Long> allowed(JsonNode step, Source source, List<Long> previous) {
        if (!step.has("max")) return List.of();
        boolean tv = step.get("media").asString().equals("TV"); int max = step.get("max").asInt();
        var works = source.works().stream().filter(w -> w.type().equals(tv ? "TV" : "MOVIE"))
                .filter(w -> (tv ? w.episode() : w.movie()) != null && (tv ? w.episode() : w.movie()) >= 1
                        && (tv ? w.episode() : w.movie()) <= max)
                .filter(w -> !flag(step, "reference") || previous.contains(w.id()))
                .sorted(Comparator.comparing((Work w) -> w.rating() == null ? 0d : w.rating()).reversed()
                        .thenComparing(Comparator.comparing((Work w) -> w.views() == null ? 0L : w.views()).reversed()).thenComparingLong(Work::id))
                .limit(12).toList();
        if (flag(step, "shortest")) {
            int min = works.stream().mapToInt(Work::movie).min().orElseThrow();
            works = works.stream().filter(w -> w.movie() == min).toList();
        }
        return works.stream().map(Work::id).toList();
    }
    void install(Source source) throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
            try (var query = connection.prepareStatement("insert into contents(id,type,title,overview,genres,release_date,rating,view_count,runtime_minutes,episode_runtime_minutes) values(?,?,?,?,?,?,?,?,?,?)")) {
                for (var w : source.works()) {
                    query.setLong(1, w.id()); query.setString(2, w.type()); query.setString(3, w.title()); query.setString(4, w.overview());
                    query.setString(5, w.genres()); query.setObject(6, w.released()); query.setObject(7, w.rating()); query.setObject(8, w.views());
                    query.setObject(9, w.movie()); query.setObject(10, w.episode()); query.executeUpdate(); copiedIds.add(w.id());
                }
            }
        }
        owner = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.invalid").nickname("시간평가").password("synthetic-hash").build()).getId();
    }
    @AfterEach void cleanup() {
        for (long room : rooms) history.delete(owner, room);
        if (owner != null) users.deleteById(owner);
        contents.deleteAllById(copiedIds);
    }
    static Source synthetic() {
        var works = new ArrayList<Work>();
        for (int i = 0; i < 6; i++) works.add(new Work(i + 1, "MOVIE", "공개 영화 " + i, "친구들의 모험 이야기", "drama",
                LocalDate.of(2020, 1, 1), 5d, (long)i, i == 5 ? null : List.of(81,90,119,120,142).get(i), null));
        works.add(new Work(7, "TV", "공개 시리즈", "친구들의 일상 이야기", "comedy", LocalDate.of(2020,1,1), 5d, 0L, null, 25));
        works.add(new Work(8, "TV", "시간 미등록 시리즈", "기록 이야기", "drama", LocalDate.of(2020,1,1), 10d, 9L, null, null));
        return new Source(works, Map.of(1L,"runtime_minutes",2L,"runtime_minutes",3L,"runtime_minutes",4L,"runtime_minutes",5L,"runtime_minutes",7L,"episode_runtime_minutes"));
    }
    @Test void frozenQuestionsExerciseRealServicesAndStoredFollowupsWithoutNetwork() throws Exception {
        var rows = run(synthetic(), null, null);
        assertEquals(8, rows.size()); assertTrue(rows.stream().allMatch(r -> "PASS".equals(r.get("status"))));
    }
    @Test void oracleRejectsInclusiveTwoHoursDroppedReferenceAndWrongTimeUnit() throws Exception {
        var strict = steps().get(2); var context = List.of(new AiMessage(AiMessage.Role.USER, strict.get("question").asString()));
        var wrongBoundary = new ChatPlan(expectedPlan(strict).requests(), false,
                List.of(c(MEDIA, "MOVIE", "영화"), c(MINUTES_MAX, "120", "2시간 미만")));
        assertThrows(AssertionError.class, () -> verifyPlan(strict, wrongBoundary, context));
        var reference = steps().get(1); var expected = expectedPlan(reference);
        var droppedReference = new ChatPlan(expected.requests(), false, expected.constraints().stream().filter(c -> c.kind() != REFERENCE).toList());
        assertThrows(AssertionError.class, () -> verifyPlan(reference, droppedReference, context));
        var episode = steps().get(3);
        var wrongUnit = new ChatPlan(expectedPlan(episode).requests(), false,
                List.of(c(MEDIA, "TV", "시리즈"), c(MINUTES_MAX, "25", "25분 이하")));
        assertThrows(AssertionError.class, () -> verifyPlan(episode, wrongUnit, context));
    }
    @Test @EnabledIfSystemProperty(named = "chatRuntimeModelCatalog", matches = "true")
    void actualPublicCatalogRehearsalIsNotModelQualityAcceptance() throws Exception { actualCatalog(false); }

    @Test @EnabledIfSystemProperty(named = "chatRuntimeModelLive", matches = "true")
    void actualModelUsesActualPublicRuntimeDataAndStoredFollowups() throws Exception {
        boolean key = present("CHAT_EVAL_OPENAI_API_KEY"), model = present("CHAT_EVAL_MODEL");
        if (!key || !model) {
            writeReport("setup", Map.of("qualityGate", "SETUP_REQUIRED", "keyConfigured", key, "modelConfigured", model,
                    "providerRequests", 0, "results", List.of()));
            fail("Dedicated evaluation environment missing; use the documented private-terminal launcher");
        }
        actualCatalog(true);
    }
    static boolean present(String name) { String value = System.getenv(name); return value != null && !value.isBlank(); }
    void actualCatalog(boolean live) throws Exception {
        try (var database = new Database()) {
            Source source = database.readPublic(); String before = fingerprint(source);
            try {
                if (live) {
                    var p = new AiProperties(); p.setEnabled(true); p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY"));
                    p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
                    try (var session = new LiveSession(p)) { assertPassed(run(source, session.client, session.budget)); }
                } else assertPassed(run(source, null, null));
            } finally { assertEquals(before, fingerprint(database.readPublic()), "Read-only source snapshot changed"); }
        } catch (java.sql.SQLException e) { throw new IllegalStateException("Public catalog check failed; driver details withheld"); }
    }
    static void assertPassed(List<Map<String, Object>> rows) {
        assertEquals(8, rows.size()); assertTrue(rows.stream().allMatch(r -> "PASS".equals(r.get("status"))), "Inspect sanitized runtime-model report");
    }
    List<Map<String, Object>> run(Source source, AiClient delegate, RuntimeModelBudget budget) throws Exception {
        reset(client); install(source);
        var rows = new ArrayList<Map<String, Object>>(); var byStory = new HashMap<String, Long>();
        boolean stopped = false;
        try {
            for (var step : steps()) {
                var row = new LinkedHashMap<String, Object>(); rows.add(row);
                row.put("id", step.get("id").asString()); row.put("status", "NOT_RUN");
                if (stopped) continue;
                String story = step.get("story").asString(), question = step.get("question").asString();
                long room = byStory.computeIfAbsent(story, k -> { long id = history.create(owner).id(); rooms.add(id); return id; });
                var snapshot = history.prepare(owner, room, question);
                var previous = snapshot.context().stream().filter(m -> m.role() == AiMessage.Role.ASSISTANT).reduce((a,b) -> b).map(AiMessage::contentIds).orElse(List.of());
                var expectedIds = allowed(step, source, previous); row.put("allowedIds", expectedIds);
                int[] generations = {0}; long start = System.nanoTime();
                doAnswer(call -> {
                    row.put("check", "classification"); assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    var plan = delegate == null ? expectedPlan(step) : delegate.classify(call.getArgument(0), call.getArgument(1));
                    row.put("plan", plan); verifyPlan(step, plan, snapshot.context()); return plan;
                }).when(client).classify(anyList(), any());
                doAnswer(call -> {
                    row.put("check", "candidates"); generations[0]++;
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); CatalogTools tools = call.getArgument(1);
                    assertEquals(expectedIds, tools.candidates().stream().map(CatalogCandidate::id).toList());
                    if (delegate != null) return delegate.recommendWithTools(call.getArgument(0), tools, call.getArgument(2));
                    var work = tools.candidates().get(0);
                    String quote = work.title().substring(0, work.title().offsetByCodePoints(0, Math.min(20, work.title().codePointCount(0, work.title().length()))));
                    return new CatalogSelection(List.of(work.id()), ZERO, Map.of(work.id(), new CatalogRationale(
                            List.of(new CatalogRationale.Evidence(CatalogRationale.Field.TITLE, quote)), "등록된 시간 조건에 맞는 작품이에요.")));
                }).when(client).recommendWithTools(anyList(), any(), any());
                try {
                    row.put("check", "reply"); var turn = service.reply(owner, room, question);
                    row.put("check", "answer-and-storage");
                    var ids = history.prepare(owner, room, "다음 질문").context().get(snapshot.context().size()).contentIds();
                    row.put("selectedIds", ids); row.put("usage", turn.usage());
                    assertEquals(!expectedIds.isEmpty(), turn.catalogGrounded());
                    if (expectedIds.isEmpty()) assertTrue(ids.isEmpty());
                    else { assertFalse(ids.isEmpty()); assertTrue(expectedIds.containsAll(ids)); }
                    String answer = turn.messages().get(1).text();
                    if (!ids.isEmpty()) {
                        assertKnown(ids, source, step.get("media").asString().equals("TV"), step.get("max").asInt());
                        assertTrue(answer.contains(step.get("media").asString().equals("TV") ? "회당 러닝타임(대표값):" : "러닝타임:"));
                        if (step.get("media").asString().equals("TV")) assertTrue(answer.contains("회차별 차이"));
                    } else if (flag(step, "unverifiable")) assertTrue(answer.contains("확인하거나 보장"));
                    else if (flag(step, "clarify")) { assertTrue(answer.contains("회당")); assertTrue(answer.contains("전체")); }
                    else assertTrue(answer.contains("미등록"));
                    assertEquals(expectedIds.isEmpty() ? 0 : 1, generations[0]);
                    assertEquals(snapshot.messageCount() + 2, history.history(owner, room, null).messages().size());
                    row.put("status", "PASS"); row.remove("check");
                } catch (ChatException e) { row.put("status", e.code()); stopped = true; }
                catch (AssertionError e) { row.put("status", "EXPECTATION_MISMATCH"); stopped = true; }
                catch (RuntimeException e) { row.put("status", "TEST_ERROR"); row.put("errorType", e.getClass().getSimpleName()); stopped = true; }
                row.put("latencyMs", (System.nanoTime() - start) / 1_000_000); row.put("generationCalls", generations[0]);
                if (!"PASS".equals(row.get("status"))) row.put("messageDelta", history.history(owner, room, null).messages().size() - snapshot.messageCount());
                if (delegate != null && !stopped) Thread.sleep(1000);
            }
        } finally {
            var report = new LinkedHashMap<String, Object>();
            report.put("evaluation", "runtime-model-v1"); report.put("realModel", delegate != null);
            report.put("model", delegate == null ? "scripted" : System.getenv("CHAT_EVAL_MODEL"));
            report.put("sourceFingerprint", fingerprint(source)); report.put("catalogRows", source.works().size());
            report.put("qualityGate", delegate == null ? "REAL_MODEL_REQUIRED" : rows.size() == 8 && rows.stream().allMatch(r -> "PASS".equals(r.get("status"))) ? "PASS" : "FAIL");
            report.put("scriptedContractGate", delegate == null && rows.size() == 8 && rows.stream().allMatch(r -> "PASS".equals(r.get("status"))) ? "PASS" : "NOT_APPLICABLE");
            report.put("providerRequests", budget == null ? 0 : budget.requests);
            report.put("providerInputTokens", budget == null ? 0 : budget.inputTokens); report.put("providerOutputTokens", budget == null ? 0 : budget.outputTokens);
            report.put("reservedOutputTokens", budget == null ? 0 : budget.reservedOutput);
            report.put("requestCharCount", budget == null ? 0 : budget.chars);
            report.put("applicationWrites", false); report.put("applicationPrivateRowsRead", false); report.put("results", rows);
            writeReport(delegate == null ? "scripted" : "live", report);
        }
        return rows;
    }
    static void writeReport(String mode, Map<String, Object> report) throws Exception {
        var output = new LinkedHashMap<>(report);
        output.put("corpusSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(corpus())));
        output.put("finishedAt", Instant.now().toString()); Files.createDirectories(DIRECTORY);
        Files.writeString(DIRECTORY.resolve(mode + "-" + Instant.now().toString().replaceAll("[^0-9]", "") + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    }
    static final class LiveSession implements AutoCloseable {
        final com.openai.client.OpenAIClient sdk;
        final AiClient client;
        final RuntimeModelBudget budget = new RuntimeModelBudget();
        LiveSession(AiProperties properties) {
            sdk = AiConfig.createSdk(properties, "https://api.openai.com/v1");
            var intercepted = mock(com.openai.client.OpenAIClient.class); var resource = spy(sdk.responses());
            when(intercepted.responses()).thenReturn(resource);
            doAnswer(call -> {
                ResponseCreateParams params = call.getArgument(0);
                budget.reserve(params.toString().length(), params.maxOutputTokens().orElseThrow());
                var response = sdk.responses().create(params, (RequestOptions) call.getArgument(1));
                response.usage().ifPresent(u -> { budget.inputTokens += u.inputTokens(); budget.outputTokens += u.outputTokens(); });
                return response;
            }).when(resource).create(any(ResponseCreateParams.class), any(RequestOptions.class));
            client = new OpenAiClient(intercepted, properties);
        }
        @Override public void close() { sdk.close(); }
    }
}
