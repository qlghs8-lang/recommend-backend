package com.example.recommend.chat.config;

import com.example.recommend.RecommendApplication;
import com.example.recommend.chat.client.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.*;
import com.example.recommend.domain.User;
import com.example.recommend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.support.StandardServletEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.example.recommend.chat.client.RecommendationConstraint.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real public catalog is read-only. JWT/chat writes and mutation probes use a newly owned schema. */
@EnabledIfSystemProperty(named = "chatRuntimeAcceptance", matches = "true")
class RuntimeRecommendationAcceptanceTest {
    static final Path OUTPUT = Path.of("target/runtime-recommendation-acceptance");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PIN = "280a9a49f6145a2e2af40d4bcbf6f88885264544be7d50feaad061842f4e43cd";
    static final String PASSWORD = "Synthetic-Runtime-Only-2026!";
    static final String INITIAL = "두 시간 이내 영화 추천해줘";
    static final String NARROW = "그중 90분 이하로 추천해줘";
    static final String SHORTEST = "그중 가장 짧은 영화로 골라줘";
    static final String TV = "이번엔 회당 25분 이하 시리즈 추천해줘";
    static final String EMPTY = "회당 24분 이하 시리즈 추천해줘";
    static final String TOTAL = "전체 시청 시간이 두 시간 이하인 시리즈 추천해줘";
    static final String EVERY = "모든 회차가 25분 이하인 시리즈 추천해줘";
    static final String FAILURE = "90분 이하 영화 추천해줘";
    static final ChatResponse.Usage USAGE = new ChatResponse.Usage(1, 1, 2);

    record Work(long id, String type, String title, String overview, String genres, LocalDate released,
                Double rating, Long views, Integer movie, Integer episode) { }
    record Source(List<Work> works, Map<Long, String> provenance) { }
    static RecommendationConstraint c(RecommendationConstraint.Kind kind, String value, String source) {
        return new RecommendationConstraint(kind, value, source, List.of());
    }

    static class Probe {
        final AtomicInteger generations = new AtomicInteger();
        final List<Map<String, Object>> observations = new ArrayList<>();
        Work unknown;
        boolean mutateNext, escapeNext;
        Long mutatedId; Integer originalMinutes;
    }

    @TestConfiguration(proxyBeanMethods = false) static class ScriptedProvider {
        @Bean Probe runtimeProbe() { return new Probe(); }
        @Bean @Primary AiClient runtimeClient(Probe probe, DataSource dataSource) {
            return new AiClient() {
                @Override public ChatPlan classify(List<AiMessage> context, TurnBudget budget) {
                    budget.consume(USAGE);
                    String current = context.get(context.size() - 1).text();
                    List<RecommendationConstraint> conditions = switch (current) {
                        case INITIAL -> List.of(c(MEDIA, "MOVIE", "영화"), c(MINUTES_MAX, "120", "두 시간 이내"));
                        case NARROW -> List.of(c(MEDIA, "MOVIE", "영화"), c(MINUTES_MAX, "90", "90분 이하"), c(REFERENCE, "ONLY_LAST", "그중"));
                        case SHORTEST -> List.of(c(MEDIA, "MOVIE", "영화"), c(MINUTES_MAX, "90", "90분 이하"),
                                c(REFERENCE, "ONLY_LAST", "그중"), c(ORDER, "SHORTER_MOVIE", "가장 짧은"));
                        case TV -> List.of(c(MEDIA, "TV", "시리즈"), c(EPISODE_MINUTES_MAX, "25", "회당 25분 이하"));
                        case EMPTY -> List.of(c(MEDIA, "TV", "시리즈"), c(EPISODE_MINUTES_MAX, "24", "회당 24분 이하"));
                        case TOTAL -> List.of(c(MEDIA, "TV", "시리즈"), c(UNVERIFIABLE, "series-total", "전체 시청 시간이 두 시간 이하"));
                        case EVERY -> List.of(c(MEDIA, "TV", "시리즈"), c(UNVERIFIABLE, "every-episode-runtime", "모든 회차가 25분 이하"));
                        case FAILURE -> List.of(c(MEDIA, "MOVIE", "영화"), c(MINUTES_MAX, "90", "90분 이하"));
                        default -> throw new AssertionError("Unexpected synthetic question");
                    };
                    return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY)), false, conditions);
                }
                @Override public CatalogSelection recommendWithTools(List<AiMessage> context, CatalogTools tools, TurnBudget budget) {
                    budget.consume(USAGE); probe.generations.incrementAndGet();
                    var constraints = tools.constraints();
                    assertFalse(constraints.isEmpty());
                    var criteria = new RecommendationCriteria(constraints, Set.of(), Set.of(), false);
                    var initial = tools.candidates(); assertFalse(initial.isEmpty()); assertTrue(initial.size() <= 12);
                    assertTrue(initial.stream().allMatch(criteria::matches));
                    var unknown = JSON.readTree(tools.execute("get_content", "{\"contentId\":" + probe.unknown.id() + "}"));
                    assertFalse(unknown.path("found").asBoolean());
                    var search = JSON.readTree(tools.execute("search_catalog", JSON.writeValueAsString(Map.of("query", probe.unknown.title()))));
                    assertTrue(search.isArray() && search.size() <= 12);
                    for (var row : search) {
                        assertNotEquals(probe.unknown.id(), row.get("id").asLong());
                        Integer max = constraints.stream().filter(k -> k.kind() == MINUTES_MAX || k.kind() == EPISODE_MINUTES_MAX)
                                .map(k -> Integer.parseInt(k.value())).min(Integer::compareTo).orElse(1440);
                        var field = row.get(constraints.stream().anyMatch(k -> k.kind() == EPISODE_MINUTES_MAX) ? "episodeRuntimeMinutes" : "runtimeMinutes");
                        assertTrue(field != null && !field.isNull() && field.asInt() > 0 && field.asInt() <= max);
                    }
                    var detail = JSON.readTree(tools.execute("get_content", "{\"contentId\":" + initial.get(0).id() + "}"));
                    assertEquals(initial.get(0).id(), detail.get("content").get("id").asLong());
                    var candidates = tools.candidates(); assertTrue(candidates.stream().allMatch(criteria::matches));
                    probe.observations.add(Map.of("constraints", constraints.stream().map(k -> k.kind() + "=" + k.value()).toList(),
                            "candidateIds", candidates.stream().map(CatalogCandidate::id).toList(), "unknownToolRejected", true));
                    if (probe.escapeNext) { probe.escapeNext = false; return new CatalogSelection(List.of(probe.unknown.id()), USAGE); }
                    boolean episode = constraints.stream().anyMatch(k -> k.kind().name().startsWith("EPISODE_"));
                    var sorted = candidates.stream().sorted(Comparator.comparingInt((CatalogCandidate w) -> episode ? w.episodeRuntimeMinutes() : w.runtimeMinutes())
                            .thenComparingLong(CatalogCandidate::id)).toList();
                    var chosen = new ArrayList<CatalogCandidate>(); chosen.add(sorted.get(0));
                    if (context.get(context.size() - 1).text().equals(INITIAL) && sorted.size() > 1) chosen.add(sorted.get(sorted.size() - 1));
                    var reasons = new LinkedHashMap<Long, CatalogRationale>();
                    for (var work : chosen) {
                        String quote = work.title().substring(0, work.title().offsetByCodePoints(0, Math.min(20, work.title().codePointCount(0, work.title().length()))));
                        reasons.put(work.id(), new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.TITLE, quote)),
                                "등록된 공개 시간과 요청 조건을 대조했어요."));
                    }
                    if (probe.mutateNext) {
                        probe.mutateNext = false; probe.mutatedId = chosen.get(0).id(); probe.originalMinutes = chosen.get(0).runtimeMinutes();
                        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("update contents set runtime_minutes=121 where id=?")) {
                            statement.setLong(1, probe.mutatedId); assertEquals(1, statement.executeUpdate());
                        } catch (SQLException e) { throw new IllegalStateException("Scratch mutation failed; details withheld"); }
                    }
                    return new CatalogSelection(chosen.stream().map(CatalogCandidate::id).toList(), USAGE, reasons);
                }
                @Override public ChatResponse reply(List<AiMessage> context) { throw new AssertionError("Unexpected free-form branch"); }
            };
        }
    }

    static final class Database implements AutoCloseable {
        final String url, username, password, schema;
        final String scratch = "recommend_runtime_acceptance_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        boolean created;
        Database() throws Exception {
            var properties = new Properties();
            Path execution = Path.of(System.getProperty("chatExecutionRoot", "C:/Users/jaehyeon/eclipse-workspace/recommend"));
            try (var reader = Files.newBufferedReader(execution.resolve("src/main/resources/application.properties"))) { properties.load(reader); }
            var env = new StandardEnvironment();
            url = env.resolveRequiredPlaceholders(properties.getProperty("spring.datasource.url"));
            username = env.resolveRequiredPlaceholders(properties.getProperty("spring.datasource.username"));
            password = env.resolveRequiredPlaceholders(properties.getProperty("spring.datasource.password"));
            var address = URI.create(url.substring(5));
            if (!url.startsWith("jdbc:mysql://") || !Set.of("127.0.0.1", "localhost").contains(address.getHost())
                    || address.getPort() != 3306 || !address.getPath().equals("/myproject")) throw new IllegalStateException("Only configured local application DB permitted");
            schema = address.getPath().substring(1);
        }
        Connection application() throws SQLException { return DriverManager.getConnection(url, username, password); }
        String scratchUrl() { return "jdbc:mysql://127.0.0.1:3306/" + scratch + "?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"; }
        Source readPublic() throws SQLException {
            try (var connection = application()) {
                connection.setReadOnly(true); connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); connection.setAutoCommit(false);
                var works = new ArrayList<Work>(); var provenance = new LinkedHashMap<Long, String>();
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(8);
                    try (var rows = statement.executeQuery("select id,type,title,overview,genres,release_date,rating,view_count,runtime_minutes,episode_runtime_minutes from contents order by id limit 5001")) {
                        while (rows.next()) works.add(new Work(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5),
                                rows.getObject(6, LocalDate.class), rows.getObject(7, Double.class), rows.getObject(8, Long.class),
                                rows.getObject(9, Integer.class), rows.getObject(10, Integer.class)));
                    }
                    try (var rows = statement.executeQuery("select content_id,target_field,minutes,proposal_sha256 from catalog_runtime_enrichments order by content_id,target_field limit 5001")) {
                        while (rows.next()) {
                            assertEquals(PIN, rows.getString(4));
                            long id = rows.getLong(1); String field = rows.getString(2); int minutes = rows.getInt(3);
                            var work = works.stream().filter(w -> w.id() == id).findFirst().orElseThrow();
                            assertEquals(field.equals("runtime_minutes") ? work.movie() : work.episode(), minutes);
                            assertNull(provenance.put(id, field));
                        }
                    }
                }
                connection.rollback(); assertEquals(468, works.size()); assertEquals(21, provenance.size());
                assertEquals(20, works.stream().filter(w -> w.type().equals("MOVIE") && w.movie() != null).count());
                assertEquals(1, works.stream().filter(w -> w.type().equals("TV") && w.episode() != null).count());
                return new Source(List.copyOf(works), Map.copyOf(provenance));
            }
        }
        void allocate() throws SQLException {
            if (!scratch.matches("recommend_runtime_acceptance_[0-9a-f]{12}") || scratch.equals(schema)) throw new IllegalStateException("Unsafe scratch name");
            try (var connection = application(); var query = connection.prepareStatement("select count(*) from information_schema.schemata where schema_name=?")) {
                query.setString(1, scratch); try (var rows = query.executeQuery()) { rows.next(); assertEquals(0, rows.getInt(1)); }
                try (var statement = connection.createStatement()) { statement.execute("create database `" + scratch + "` character set utf8mb4"); created = true; }
            }
        }
        @Override public void close() throws SQLException {
            if (created && scratch.matches("recommend_runtime_acceptance_[0-9a-f]{12}") && !scratch.equals(schema)) {
                try (var connection = application(); var statement = connection.createStatement()) { statement.execute("drop database `" + scratch + "`"); }
                created = false;
                try (var connection = application(); var query = connection.prepareStatement("select count(*) from information_schema.schemata where schema_name=?")) {
                    query.setString(1, scratch); try (var rows = query.executeQuery()) { rows.next(); assertEquals(0, rows.getInt(1)); }
                }
            }
        }
        @Override public String toString() { return "Database[server-only]"; }
    }

    static void copyPublic(DataSource target, Source source) throws SQLException {
        try (var connection = target.getConnection(); var query = connection.prepareStatement("insert into contents(id,type,title,overview,genres,release_date,rating,view_count,runtime_minutes,episode_runtime_minutes) values(?,?,?,?,?,?,?,?,?,?)")) {
            connection.setAutoCommit(false);
            for (var w : source.works()) {
                query.setLong(1, w.id()); query.setString(2, w.type()); query.setString(3, w.title()); query.setString(4, w.overview());
                query.setString(5, w.genres()); query.setObject(6, w.released()); query.setObject(7, w.rating()); query.setObject(8, w.views());
                query.setObject(9, w.movie()); query.setObject(10, w.episode()); query.addBatch();
            }
            assertEquals(468, query.executeBatch().length); connection.commit();
        }
    }

    @Test void actualRuntimeCatalogThroughSqlToolsJwtHttpAndAtomicValidation() throws Exception {
        Files.createDirectories(OUTPUT);
        var report = new LinkedHashMap<String, Object>(); var checks = new ArrayList<String>();
        report.put("provider", "scripted; no paid calls"); report.put("applicationWrites", false); report.put("applicationPrivateRowsRead", false);
        report.put("status", "NOT_COMPLETED"); report.put("checks", checks);
        Database database = null;
        try {
            database = new Database();
            try (var db = database) {
                Source source = db.readPublic(); report.put("sourceFingerprint", fingerprint(source)); report.put("scratchSchema", db.scratch);
                report.put("catalogRows", source.works().size()); report.put("knownDurations", source.provenance().size());
                db.allocate();
                var overrides = new LinkedHashMap<String, Object>();
                overrides.put("server.port", "0"); overrides.put("server.address", "127.0.0.1");
                overrides.put("spring.datasource.url", db.scratchUrl()); overrides.put("spring.datasource.username", db.username); overrides.put("spring.datasource.password", db.password);
                overrides.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver"); overrides.put("spring.jpa.hibernate.ddl-auto", "create");
                overrides.put("spring.jpa.database-platform", "org.hibernate.dialect.MySQLDialect"); overrides.put("spring.jpa.show-sql", "false");
                overrides.put("spring.main.banner-mode", "off"); overrides.put("spring.security.user.password", PASSWORD);
                overrides.put("JWT_SECRET", "synthetic-runtime-only-key-not-for-existing-accounts-2026");
                overrides.put("app.ai.enabled", "true"); overrides.put("app.ai.api-key", "synthetic-no-network"); overrides.put("app.ai.model", "synthetic");
                overrides.put("app.ai.requests-per-minute", "60"); overrides.put("app.ai.global-requests-per-minute", "600");
                overrides.put("app.phone.store", "memory"); overrides.put("app.upload-dir", OUTPUT.resolve("uploads").toAbsolutePath().toString());
                var environment = new StandardServletEnvironment(); environment.getPropertySources().addFirst(new MapPropertySource("runtimeAcceptance", overrides));
                try (var app = new SpringApplicationBuilder(RecommendApplication.class, ScriptedProvider.class).environment(environment).run()) {
                    assertEquals(db.scratchUrl(), app.getEnvironment().getProperty("spring.datasource.url"));
                    DataSource dataSource = app.getBean(DataSource.class); copyPublic(dataSource, source);
                    var probe = app.getBean(Probe.class);
                    probe.unknown = source.works().stream().filter(w -> w.type().equals("MOVIE") && w.movie() == null && w.title().length() <= 200).findFirst().orElseThrow();
                    var retriever = app.getBean(CatalogRetriever.class);
                    int boundary = source.works().stream().filter(w -> w.movie() != null).mapToInt(Work::movie).min().orElseThrow();
                    for (var entry : List.of(new int[]{90,0}, new int[]{119,0}, new int[]{120,0}, new int[]{boundary,0}, new int[]{boundary-1,0},
                            new int[]{25,1}, new int[]{24,1}, new int[]{26,1})) {
                        int minutes = entry[0]; boolean episode = entry[1] == 1;
                        String media = episode ? "TV" : "MOVIE";
                        var conditions = List.of(c(MEDIA, media, "검사"), c(episode ? EPISODE_MINUTES_MAX : MINUTES_MAX, ""+minutes, "검사"));
                        var criteria = new RecommendationCriteria(conditions, Set.of(), Set.of(), false);
                        var found = retriever.retrieve(List.of(new AiMessage(AiMessage.Role.USER, "검사")), criteria);
                        var expected = source.works().stream().filter(w -> w.type().equals(media))
                                .filter(w -> (episode ? w.episode() : w.movie()) != null && (episode ? w.episode() : w.movie()) >= 1
                                        && (episode ? w.episode() : w.movie()) <= minutes)
                                .sorted(Comparator.comparing((Work w) -> w.rating() == null ? 0d : w.rating()).reversed()
                                        .thenComparing(Comparator.comparing((Work w) -> w.views() == null ? 0L : w.views()).reversed()).thenComparingLong(Work::id))
                                .limit(12).map(Work::id).toList();
                        assertEquals(expected, found.stream().map(CatalogCandidate::id).toList());
                        assertTrue(found.stream().allMatch(criteria::matches));
                        assertTrue(found.stream().allMatch(w -> source.provenance().containsKey(w.id())));
                        checks.add("sql-" + media + "-max-" + minutes);
                    }
                    var users = app.getBean(UserRepository.class); var encoder = app.getBean(PasswordEncoder.class);
                    var owner = users.saveAndFlush(User.builder().email("runtime-owner@example.invalid").nickname("시간검사").password(encoder.encode(PASSWORD)).onboardingDone(true).build());
                    users.saveAndFlush(User.builder().email("runtime-other@example.invalid").nickname("다른검사").password(encoder.encode(PASSWORD)).onboardingDone(true).build());
                    var api = new Api(app.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
                    assertEquals(401, api.call("GET", "/api/chat/rooms", null, null).statusCode()); checks.add("unauthenticated-401");
                    String token = api.login("runtime-owner@example.invalid"), other = api.login("runtime-other@example.invalid");
                    var created = api.call("POST", "/api/chat/rooms", token, ""); assertEquals(201, created.statusCode());
                    long room = JSON.readTree(created.body()).get("id").asLong(); String path = "/api/chat/rooms/" + room + "/messages";
                    var history = app.getBean(ChatHistoryStore.class);
                    var first = api.turn(path, token, INITIAL); var firstIds = ids(history, owner.getId(), room);
                    assertEquals(2, firstIds.size()); assertKnown(firstIds, source, false, 120); checks.add("http-movie-max-120");
                    String firstText = first.get("text").asString(); assertTrue(firstText.contains("러닝타임:"));
                    api.turn(path, token, NARROW); var narrowed = ids(history, owner.getId(), room);
                    assertFalse(narrowed.isEmpty()); assertTrue(firstIds.containsAll(narrowed)); assertKnown(narrowed, source, false, 90); checks.add("http-reference-narrow-90");
                    api.turn(path, token, SHORTEST); var shortest = ids(history, owner.getId(), room);
                    int min = source.works().stream().filter(w -> narrowed.contains(w.id())).mapToInt(Work::movie).min().orElseThrow();
                    assertFalse(shortest.isEmpty()); assertTrue(narrowed.containsAll(shortest));
                    assertTrue(source.works().stream().filter(w -> shortest.contains(w.id())).allMatch(w -> w.movie() == min)); checks.add("http-shortest-within-last");
                    var tv = api.turn(path, token, TV); var television = ids(history, owner.getId(), room);
                    assertEquals(1, television.size()); assertKnown(television, source, true, 25);
                    assertTrue(tv.get("text").asString().contains("회당 러닝타임(대표값): 25분"));
                    assertTrue(tv.get("text").asString().contains("회차별 차이")); checks.add("http-reset-to-tv-episode-25");
                    int calls = probe.generations.get();
                    var empty = api.turn(path, token, EMPTY); assertTrue(ids(history, owner.getId(), room).isEmpty());
                    assertTrue(empty.get("text").asString().contains("미등록")); assertEquals(calls, probe.generations.get()); checks.add("http-no-match-no-generation");
                    for (String question : List.of(TOTAL, EVERY)) {
                        var response = api.turn(path, token, question); assertTrue(ids(history, owner.getId(), room).isEmpty());
                        assertTrue(response.get("text").asString().contains("확인하거나 보장")); assertEquals(calls, probe.generations.get());
                        checks.add(question.equals(TOTAL) ? "http-no-series-total-estimate" : "http-no-every-episode-guarantee");
                    }
                    int storedCount = history.history(owner.getId(), room, null).messages().size(); assertEquals(14, storedCount);
                    probe.mutateNext = true;
                    var failure = api.call("POST", path, token, JSON.writeValueAsString(Map.of("message", FAILURE)));
                    assertFalse(probe.mutateNext); assertNotNull(probe.mutatedId);
                    int currentMinutes;
                    try (var connection = dataSource.getConnection(); var query = connection.prepareStatement("select runtime_minutes from contents where id=?")) {
                        query.setLong(1, probe.mutatedId); try (var rows = query.executeQuery()) { assertTrue(rows.next()); currentMinutes = rows.getInt(1); }
                    }
                    report.put("runtimeMutation", Map.of("id", probe.mutatedId, "initialMinutes", probe.originalMinutes, "databaseMinutes", currentMinutes,
                            "httpStatus", failure.statusCode(), "messageCountDelta", history.history(owner.getId(), room, null).messages().size() - storedCount,
                            "responseMentionsOldMinutes", failure.body().contains("러닝타임: " + probe.originalMinutes + "분")));
                    assertEquals(121, currentMinutes);
                    assertEquals(502, failure.statusCode()); assertTrue(failure.body().contains("AI_INVALID_RESPONSE"));
                    assertEquals(storedCount, history.history(owner.getId(), room, null).messages().size()); checks.add("http-runtime-change-rejected-atomically");
                    try (var connection = dataSource.getConnection(); var query = connection.prepareStatement("update contents set runtime_minutes=? where id=?")) {
                        query.setObject(1, probe.originalMinutes); query.setLong(2, probe.mutatedId); assertEquals(1, query.executeUpdate());
                    }
                    probe.escapeNext = true;
                    assertEquals(502, api.call("POST", path, token, JSON.writeValueAsString(Map.of("message", FAILURE))).statusCode());
                    assertEquals(storedCount, history.history(owner.getId(), room, null).messages().size()); checks.add("http-unknown-id-selection-rejected-atomically");
                    assertEquals(404, api.call("GET", path, other, null).statusCode());
                    assertEquals(404, api.call("POST", path, other, JSON.writeValueAsString(Map.of("message", INITIAL))).statusCode()); checks.add("foreign-room-404");
                    var reloaded = api.call("GET", path, token, null); assertEquals(200, reloaded.statusCode());
                    assertEquals(firstText, JSON.readTree(reloaded.body()).get("messages").get(1).get("text").asString()); checks.add("stored-response-reload");
                    report.put("toolObservations", probe.observations); report.put("scriptedGenerationCalls", probe.generations.get());
                    report.put("successfulHttpTurns", 7); report.put("rejectedHttpTurns", 2);
                }
                assertEquals(fingerprint(source), fingerprint(db.readPublic())); checks.add("application-public-snapshot-preserved");
                report.put("status", "PASS");
            }
            report.put("scratchDroppedAndVerified", !database.created);
        } catch (SQLException e) { throw new IllegalStateException("Runtime acceptance DB check failed; driver details withheld");
        } finally {
            report.put("checkCount", checks.size()); report.put("finishedAt", Instant.now().toString());
            String name = "report-" + Instant.now().toString().replaceAll("[^0-9]", "") + ".json";
            Files.writeString(OUTPUT.resolve(name), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }

    static List<Long> ids(ChatHistoryStore history, long user, long room) {
        return history.prepare(user, room, "검사 다음").context().stream().filter(m -> m.role() == AiMessage.Role.ASSISTANT)
                .reduce((a, b) -> b).orElseThrow().contentIds();
    }
    static void assertKnown(List<Long> ids, Source source, boolean episode, int max) {
        assertTrue(ids.stream().allMatch(source.provenance()::containsKey));
        for (Long id : ids) {
            var w = source.works().stream().filter(row -> row.id() == id).findFirst().orElseThrow();
            assertEquals(episode ? "TV" : "MOVIE", w.type()); Integer value = episode ? w.episode() : w.movie();
            assertNotNull(value); assertTrue(value >= 1 && value <= max);
        }
    }
    static String fingerprint(Source source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                JSON.writeValueAsString(List.of(source.works(), new TreeMap<>(source.provenance()))).getBytes(StandardCharsets.UTF_8)));
    }
    static final class Api {
        final int port; final HttpClient client = HttpClient.newHttpClient();
        Api(int port) { this.port = port; }
        HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            if (token != null) request.header("Authorization", "Bearer " + token);
            return client.send(request.method(method, body == null || body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        String login(String email) throws Exception {
            var response = call("POST", "/login", null, JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD)));
            assertEquals(200, response.statusCode()); return JSON.readTree(response.body()).get("token").asString();
        }
        JsonNode turn(String path, String token, String question) throws Exception {
            var response = call("POST", path, token, JSON.writeValueAsString(Map.of("message", question)));
            assertEquals(200, response.statusCode()); assertFalse(response.body().contains("provenance"));
            var json = JSON.readTree(response.body()); assertEquals(2, json.get("messages").size());
            return json.get("messages").get(1);
        }
    }
}
