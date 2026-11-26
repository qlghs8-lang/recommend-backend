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
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.support.StandardServletEnvironment;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.example.recommend.chat.client.RecommendationConstraint.Kind.*;
import static com.example.recommend.chat.config.RuntimeRecommendationAcceptanceTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL/OSIV/JWT/React acceptance; scripted AI, newly owned schema, public source read-only. */
@EnabledIfSystemProperty(named = "chatRuntimeBrowser", matches = "true")
class RuntimeBrowserAcceptanceTest {
    static final Path DIRECTORY = Path.of("target/runtime-browser-acceptance");
    static final String STRICT = "이전 조건 모두 잊고 2시간 미만 영화 추천해줘";
    static final String AMBIGUOUS = "짧게 볼 만한 시리즈 추천해줘";
    static final String REASON = "첫 번째 추천 이유를 설명해줘";
    static final String STALE_SINGLE = "첫 번째 추천 이유 최신성 검사";
    static final String STALE_GROUP = "추천 이유 모두 최신성 검사";
    static final String UI_EMAIL = "runtime-ui@example.invalid";
    static final class BrowserProbe {
        final Set<String> injected = new HashSet<>();
        final Set<Long> changedIds = new LinkedHashSet<>();
        long compoundId;
        String compoundQuestion() { return "ID " + compoundId + " 상세와 영화와 시리즈의 차이 설명해줘"; }
        void mutate(String question, long id, DataSource target) {
            if (!injected.add(question)) return;
            try (var connection = target.getConnection(); var query = connection.prepareStatement("update contents set overview=? where id=?")) {
                assertTrue(connection.getMetaData().getURL().contains("/recommend_runtime_acceptance_"));
                query.setString(1, "격리 검사에서 변경된 최신 공개 줄거리 · " + question);
                query.setLong(2, id); assertEquals(1, query.executeUpdate()); changedIds.add(id);
            } catch (SQLException e) { throw new IllegalStateException("Owned fixture mutation failed; details withheld"); }
        }
    }
    @TestConfiguration(proxyBeanMethods = false) static class Provider {
        @Bean Probe runtimeProbe() { return new Probe(); }
        @Bean BrowserProbe browserProbe() { return new BrowserProbe(); }
        @Bean @Primary AiClient browserClient(Probe baseProbe, BrowserProbe probe, DataSource dataSource) {
            var base = new ScriptedProvider().runtimeClient(baseProbe, dataSource);
            return new AiClient() {
                @Override public ChatPlan classify(List<AiMessage> messages, TurnBudget budget) {
                    String current = messages.get(messages.size() - 1).text();
                    if (List.of(REASON, STALE_SINGLE, STALE_GROUP).contains(current)) {
                        budget.consume(USAGE);
                        return ChatPlan.single(new ChatIntent(ChatIntent.Route.RATIONALE, ChatIntent.Media.ANY,
                                null, null, null, 1, current.equals(STALE_GROUP) ? 0 : 1, null));
                    }
                    if (current.equals(probe.compoundQuestion())) {
                        budget.consume(USAGE); String detail = "ID " + probe.compoundId + " 상세";
                        return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY,
                                null, probe.compoundId, null, null, null, detail),
                                new ChatIntent(ChatIntent.Route.EXPLAIN, ChatIntent.Media.ANY, null, null, null,
                                        null, null, current.substring(detail.length()))), false);
                    }
                    if (current.equals(AMBIGUOUS)) {
                        budget.consume(USAGE); return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY)), false,
                                List.of(), new Clarification(Clarification.Reason.TV_DURATION_UNIT, "짧게 볼 만한"));
                    }
                    if (current.equals(STRICT)) {
                        budget.consume(USAGE); return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY)), false,
                                List.of(c(MEDIA, "MOVIE", "영화"), c(MINUTES_MAX, "119", "2시간 미만")));
                    }
                    return base.classify(messages, budget);
                }
                @Override public CatalogSelection recommendWithTools(List<AiMessage> messages, CatalogTools tools, TurnBudget budget) {
                    return base.recommendWithTools(messages, tools, budget);
                }
                CatalogRationale rationale(CatalogCandidate work) {
                    String quote = work.title().substring(0, work.title().offsetByCodePoints(0, Math.min(20, work.title().codePointCount(0, work.title().length()))));
                    return new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.TITLE, quote)), "공개 카탈로그의 작품을 대조했어요.");
                }
                @Override public CatalogRationale justify(String original, String question, CatalogCandidate work, TurnBudget budget) {
                    budget.consume(USAGE); if (question.equals(STALE_SINGLE)) probe.mutate(question, work.id(), dataSource);
                    return rationale(work);
                }
                @Override public Map<Long, CatalogRationale> justifyMany(String original, String question, List<CatalogCandidate> works, TurnBudget budget) {
                    budget.consume(USAGE); if (question.equals(STALE_GROUP)) probe.mutate(question, works.get(1).id(), dataSource);
                    var reasons = new LinkedHashMap<Long, CatalogRationale>(); works.forEach(w -> reasons.put(w.id(), rationale(w))); return reasons;
                }
                @Override public ChatResponse explain(String question, TurnBudget budget) {
                    budget.consume(USAGE); probe.mutate(probe.compoundQuestion(), probe.compoundId, dataSource);
                    return new ChatResponse("영화와 시리즈의 형식에 대한 합성 일반 설명입니다.", USAGE, false);
                }
                @Override public ChatResponse reply(List<AiMessage> context) { throw new AssertionError("Unplanned synthetic provider branch"); }
            };
        }
    }
    @Test void actualMySqlHttpAndOptionalReactRuntimeConversation() throws Exception {
        Files.createDirectories(DIRECTORY);
        boolean browser = Boolean.getBoolean("chatRuntimeBrowserUi");
        Path ready = DIRECTORY.resolve("browser-ready.json"), finished = DIRECTORY.resolve("browser-complete.json");
        if (browser && (Files.exists(ready) || Files.exists(finished))) throw new IllegalStateException("Archive old browser handshake before another UI run");
        var report = new LinkedHashMap<String, Object>(); report.put("status", "NOT_COMPLETED");
        report.put("providerCalls", 0); report.put("applicationPrivateRowsRead", false); report.put("applicationWrites", false);
        Database database = null;
        try {
            database = new Database();
            try (var db = database) {
                var source = db.readPublic(); String before = fingerprint(source); report.put("sourceFingerprint", before);
                report.put("catalogRows", source.works().size()); report.put("knownDurations", source.provenance().size());
                db.allocate(); report.put("scratchSchema", db.scratch);
                var overrides = new LinkedHashMap<String, Object>();
                overrides.put("server.port", "0"); overrides.put("server.address", "127.0.0.1");
                overrides.put("spring.datasource.url", db.scratchUrl()); overrides.put("spring.datasource.username", db.username); overrides.put("spring.datasource.password", db.password);
                overrides.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver"); overrides.put("spring.jpa.hibernate.ddl-auto", "create");
                overrides.put("spring.jpa.database-platform", "org.hibernate.dialect.MySQLDialect"); overrides.put("spring.jpa.open-in-view", "true"); overrides.put("spring.jpa.show-sql", "false");
                overrides.put("spring.main.banner-mode", "off"); overrides.put("spring.security.user.password", PASSWORD);
                overrides.put("JWT_SECRET", "synthetic-runtime-browser-only-never-for-existing-users-2026");
                overrides.put("app.ai.enabled", "true"); overrides.put("app.ai.api-key", "synthetic-no-network"); overrides.put("app.ai.model", "synthetic");
                overrides.put("app.ai.requests-per-minute", "60"); overrides.put("app.ai.global-requests-per-minute", "600");
                overrides.put("app.phone.store", "memory"); overrides.put("app.upload-dir", DIRECTORY.resolve("uploads").toAbsolutePath().toString());
                var environment = new StandardServletEnvironment(); environment.getPropertySources().addFirst(new MapPropertySource("runtimeBrowser", overrides));
                try (var app = new SpringApplicationBuilder(RecommendApplication.class, Provider.class).environment(environment).run()) {
                    var ds = app.getBean(DataSource.class); copyPublic(ds, source);
                    app.getBean(Probe.class).unknown = source.works().stream().filter(w -> w.type().equals("MOVIE") && w.movie() == null && w.title().length() <= 200).findFirst().orElseThrow();
                    var probe = app.getBean(BrowserProbe.class); var users = app.getBean(UserRepository.class); var encoder = app.getBean(PasswordEncoder.class);
                    var owner = users.saveAndFlush(User.builder().email("runtime-http@example.invalid").nickname("시간HTTP검사").password(encoder.encode(PASSWORD)).onboardingDone(true).build());
                    var ui = users.saveAndFlush(User.builder().email(UI_EMAIL).nickname("시간화면검사").password(encoder.encode(PASSWORD)).onboardingDone(true).build());
                    users.saveAndFlush(User.builder().email("runtime-foreign@example.invalid").nickname("다른검사").password(encoder.encode(PASSWORD)).onboardingDone(true).build());
                    int port = app.getEnvironment().getRequiredProperty("local.server.port", Integer.class); var api = new Api(port);
                    var history = app.getBean(ChatHistoryStore.class); String token = api.login(owner.getEmail()), foreign = api.login("runtime-foreign@example.invalid");
                    long room = history.create(owner.getId()).id(); String path = "/api/chat/rooms/" + room + "/messages";
                    assertEquals(401, api.call("GET", path, null, null).statusCode()); assertEquals(404, api.call("GET", path, foreign, null).statusCode());
                    api.turn(path, token, INITIAL); var initialIds = ids(history, owner.getId(), room); assertEquals(2, initialIds.size()); assertKnown(initialIds, source, false, 120);
                    probe.compoundId = initialIds.get(0);
                    api.turn(path, token, NARROW); var narrowed = ids(history, owner.getId(), room); assertFalse(narrowed.isEmpty()); assertTrue(initialIds.containsAll(narrowed)); assertKnown(narrowed, source, false, 90);
                    api.turn(path, token, SHORTEST); assertFalse(ids(history, owner.getId(), room).isEmpty());
                    String television = api.turn(path, token, TV).get("text").asString();
                    assertTrue(television.contains("회당 러닝타임(대표값): 25분")); assertTrue(television.contains("회차별 차이"));
                    assertKnown(ids(history, owner.getId(), room), source, true, 25);
                    assertTrue(api.turn(path, token, EMPTY).get("text").asString().contains("미등록")); assertTrue(ids(history, owner.getId(), room).isEmpty());
                    assertTrue(api.turn(path, token, TOTAL).get("text").asString().contains("확인하거나 보장"));
                    assertTrue(api.turn(path, token, EVERY).get("text").asString().contains("확인하거나 보장"));
                    assertTrue(api.turn(path, token, AMBIGUOUS).get("text").asString().contains("회당"));
                    api.turn(path, token, STRICT); assertKnown(ids(history, owner.getId(), room), source, false, 119);
                    api.turn(path, token, REASON); assertEquals(20, history.history(owner.getId(), room, null).messages().size());
                    rejectThenRetry(api, history, owner.getId(), room, token, STALE_SINGLE);
                    long groupRoom = history.create(owner.getId()).id(); String groupPath = "/api/chat/rooms/" + groupRoom + "/messages";
                    api.turn(groupPath, token, INITIAL); rejectThenRetry(api, history, owner.getId(), groupRoom, token, STALE_GROUP);
                    rejectThenRetry(api, history, owner.getId(), room, token, probe.compoundQuestion());
                    report.put("mysqlHttp", "PASS; runtime nine turns, rationale, single/group/compound update rejection and retry, ownership");
                    // Reset only scratch metadata/one-shot mutations so UI observations are independent.
                    try (var connection = ds.getConnection(); var restore = connection.prepareStatement("update contents set overview=? where id=?")) {
                        for (long id : probe.changedIds) {
                            restore.setString(1, source.works().stream().filter(w -> w.id() == id).findFirst().orElseThrow().overview()); restore.setLong(2, id); assertEquals(1, restore.executeUpdate());
                        }
                    }
                    probe.injected.clear(); probe.changedIds.clear();
                    if (browser) {
                        write(ready, Map.of("port", port, "email", UI_EMAIL, "compoundQuestion", probe.compoundQuestion(), "sourceFingerprint", before));
                        Instant deadline = Instant.now().plusSeconds(900);
                        while (!Files.exists(finished) && Instant.now().isBefore(deadline)) Thread.sleep(250);
                        assertTrue(Files.exists(finished), "UI completion not observed within bounded window");
                        var observations = JSON.readTree(Files.readString(finished)); assertTrue(observations.get("passed").asBoolean());
                        report.put("browser", observations);
                        var rooms = history.list(ui.getId(), null).rooms(); assertFalse(rooms.isEmpty());
                        int messages = 0; for (var uiRoom : rooms) messages += history.history(ui.getId(), uiRoom.id(), null).messages().size();
                        assertEquals(observations.get("storedMessages").asInt(), messages); report.put("verifiedUiStoredMessages", messages);
                    } else report.put("browser", "NOT_RUN");
                }
                assertEquals(before, fingerprint(db.readPublic())); report.put("applicationPublicSnapshotPreserved", true);
            }
            report.put("scratchDroppedAndVerified", !database.created); report.put("status", "PASS");
        } catch (SQLException e) { throw new IllegalStateException("Runtime browser DB check failed; driver details withheld"); }
        finally { write(DIRECTORY.resolve("report-" + Instant.now().toString().replaceAll("[^0-9]", "") + ".json"), report); }
    }
    static void rejectThenRetry(Api api, ChatHistoryStore history, long user, long room, String token, String question) throws Exception {
        String path = "/api/chat/rooms/" + room + "/messages"; int before = history.history(user, room, null).messages().size();
        var failure = api.call("POST", path, token, JSON.writeValueAsString(Map.of("message", question)));
        assertEquals(502, failure.statusCode()); assertTrue(failure.body().contains("AI_INVALID_RESPONSE"));
        assertEquals(before, history.history(user, room, null).messages().size());
        api.turn(path, token, question); assertEquals(before + 2, history.history(user, room, null).messages().size());
    }
    static void write(Path path, Object value) throws Exception { Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
}
