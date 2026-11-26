package com.example.recommend.chat.config;

import com.example.recommend.RecommendApplication;
import com.example.recommend.chat.client.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.*;
import com.example.recommend.domain.*;
import com.example.recommend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.support.StandardServletEnvironment;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real OSIV/JWT/HTTP, independent JDBC commits, synthetic H2 only; no opt-in/provider key needed. */
class CatalogFreshnessAcceptanceTest {
    static final Path OUTPUT = Path.of("target/catalog-freshness");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PASSWORD = "Synthetic-Freshness-Only-2026!";
    static final ChatResponse.Usage USAGE = new ChatResponse.Usage(1, 1, 2);
    enum PathKind { SINGLE_RATIONALE, GROUP_RATIONALE, DETAIL, COMPOUND_DETAIL }
    enum Change { NONE, UPDATE, DELETE }
    static final class Probe {
        PathKind path; Change change; long first, second, target;
        boolean requestBound, mutated;
        int classificationCalls, generationCalls;
        void reset(PathKind path, Change change, long first, long second) {
            this.path = path; this.change = change; this.first = first; this.second = second;
            target = path == PathKind.GROUP_RATIONALE ? second : first;
            requestBound = false; mutated = false; classificationCalls = 0; generationCalls = 0;
        }
        void mutate(DataSource dataSource) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Provider must not hold a transaction");
            if (change == Change.NONE) return;
            assertFalse(mutated); mutated = true;
            try (var connection = dataSource.getConnection()) {
                assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:catalog_freshness_"));
                assertTrue(connection.getAutoCommit());
                String sql = change == Change.DELETE ? "delete from contents where id=?"
                        : "update contents set title='변경된 공개 제목',overview='변경된 공개 줄거리',genres='comedy',runtime_minutes=121 where id=?";
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, target); assertEquals(1, statement.executeUpdate());
                }
            } catch (SQLException e) { throw new IllegalStateException("Synthetic mutation failed; details withheld"); }
        }
    }
    @TestConfiguration(proxyBeanMethods = false) static class ScriptedProvider {
        @Bean Probe freshnessProbe() { return new Probe(); }
        @Bean @Primary AiClient freshnessClient(Probe probe, DataSource dataSource, EntityManager em, EntityManagerFactory factory) {
            return new AiClient() {
                @Override public ChatPlan classify(List<AiMessage> context, TurnBudget budget) {
                    probe.classificationCalls++; budget.consume(USAGE);
                    probe.requestBound = TransactionSynchronizationManager.hasResource(factory);
                    assertTrue(probe.requestBound, "Real HTTP request must have OSIV enabled");
                    String question = context.get(context.size() - 1).text();
                    if (probe.path == PathKind.SINGLE_RATIONALE || probe.path == PathKind.GROUP_RATIONALE)
                        return ChatPlan.single(new ChatIntent(ChatIntent.Route.RATIONALE, ChatIntent.Media.ANY,
                                null, null, null, 1, probe.path == PathKind.SINGLE_RATIONALE ? 1 : 0, null));
                    if (probe.path == PathKind.DETAIL) {
                        // Deliberately retain an earlier managed copy in this real request's EntityManager.
                        assertNotNull(em.find(Content.class, probe.first)); probe.mutate(dataSource);
                        return ChatPlan.single(new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, null, probe.first, null));
                    }
                    String detail = "ID " + probe.first + " 상세";
                    assertTrue(question.startsWith(detail));
                    return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY,
                            null, probe.first, null, null, null, detail),
                            new ChatIntent(ChatIntent.Route.EXPLAIN, ChatIntent.Media.ANY,
                                    null, null, null, null, null, question.substring(detail.length()))), false);
                }
                CatalogRationale reason(CatalogCandidate work) {
                    return new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.OVERVIEW, work.overview())),
                            "공개 줄거리를 바탕으로 설명했어요.");
                }
                @Override public CatalogRationale justify(String original, String question, CatalogCandidate work, TurnBudget budget) {
                    assertEquals("영화 추천해줘", original); probe.generationCalls++; budget.consume(USAGE);
                    probe.mutate(dataSource); return reason(work);
                }
                @Override public Map<Long, CatalogRationale> justifyMany(String original, String question, List<CatalogCandidate> works, TurnBudget budget) {
                    assertEquals("영화 추천해줘", original); probe.generationCalls++; budget.consume(USAGE);
                    assertEquals(2, works.size()); probe.mutate(dataSource);
                    var result = new LinkedHashMap<Long, CatalogRationale>(); works.forEach(w -> result.put(w.id(), reason(w))); return result;
                }
                @Override public ChatResponse explain(String question, TurnBudget budget) {
                    probe.generationCalls++; budget.consume(USAGE); probe.mutate(dataSource);
                    return new ChatResponse("두 형식의 일반 설명입니다.", USAGE, false);
                }
                @Override public ChatResponse reply(List<AiMessage> messages) { throw new AssertionError("Unplanned provider call"); }
            };
        }
    }
    @Test void realOsivRereadsAndAtomicHistoryAcrossDetailAndRationalePaths() throws Exception {
        Files.createDirectories(OUTPUT);
        var observations = new ArrayList<Map<String, Object>>();
        var report = new LinkedHashMap<String, Object>();
        report.put("provider", "scripted; zero provider requests"); report.put("database", "unique synthetic H2 only");
        report.put("applicationDatabaseAccess", false); report.put("results", observations); report.put("status", "NOT_COMPLETED");
        var overrides = new LinkedHashMap<String, Object>();
        overrides.put("server.port", "0"); overrides.put("server.address", "127.0.0.1");
        overrides.put("spring.datasource.url", "jdbc:h2:mem:catalog_freshness_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=0");
        overrides.put("spring.datasource.username", "sa"); overrides.put("spring.datasource.password", "");
        overrides.put("spring.datasource.driver-class-name", "org.h2.Driver");
        overrides.put("spring.jpa.database-platform", "org.hibernate.dialect.H2Dialect");
        overrides.put("spring.jpa.hibernate.ddl-auto", "create-drop"); overrides.put("spring.jpa.open-in-view", "true");
        overrides.put("spring.jpa.show-sql", "false"); overrides.put("spring.main.banner-mode", "off");
        overrides.put("spring.security.user.password", PASSWORD);
        overrides.put("JWT_SECRET", "synthetic-freshness-only-never-for-existing-accounts-2026");
        overrides.put("app.ai.enabled", "true"); overrides.put("app.ai.api-key", "synthetic-no-network"); overrides.put("app.ai.model", "synthetic");
        overrides.put("app.ai.requests-per-minute", "60"); overrides.put("app.ai.global-requests-per-minute", "600");
        overrides.put("app.phone.store", "memory"); overrides.put("app.upload-dir", OUTPUT.resolve("uploads").toAbsolutePath().toString());
        var environment = new StandardServletEnvironment(); environment.getPropertySources().addFirst(new MapPropertySource("catalogFreshness", overrides));
        try (var app = new SpringApplicationBuilder(RecommendApplication.class, ScriptedProvider.class).environment(environment).run()) {
            var dataSource = app.getBean(DataSource.class);
            try (var connection = dataSource.getConnection()) { assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:catalog_freshness_")); }
            var users = app.getBean(UserRepository.class); var encoder = app.getBean(PasswordEncoder.class);
            var owner = users.saveAndFlush(User.builder().email("freshness-owner@example.invalid").nickname("최신성검사").password(encoder.encode(PASSWORD)).onboardingDone(true).build());
            users.saveAndFlush(User.builder().email("freshness-other@example.invalid").nickname("다른검사").password(encoder.encode(PASSWORD)).onboardingDone(true).build());
            var contents = app.getBean(ContentRepository.class); var history = app.getBean(ChatHistoryStore.class); var probe = app.getBean(Probe.class);
            var api = new Api(app.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
            String token = api.login("freshness-owner@example.invalid"), foreign = api.login("freshness-other@example.invalid");
            long foreignRoom = history.create(owner.getId()).id();
            assertEquals(401, api.call("GET", "/api/chat/rooms", null, null).statusCode());
            assertEquals(404, api.call("POST", "/api/chat/rooms/" + foreignRoom + "/messages", foreign, JSON.writeValueAsString(Map.of("message", "검사"))).statusCode());
            assertEquals(0, probe.classificationCalls); assertTrue(history.history(owner.getId(), foreignRoom, null).messages().isEmpty());
            report.put("authorization", "PASS; 401/404 before provider, zero stored messages");
            for (var path : PathKind.values()) for (var change : Change.values()) {
                var first = contents.saveAndFlush(Content.builder().title("첫 공개 작품").type("MOVIE").genres("drama").overview("첫 공개 원본 줄거리").runtimeMinutes(90).build());
                var second = contents.saveAndFlush(Content.builder().title("둘째 공개 작품").type("MOVIE").genres("drama").overview("둘째 공개 원본 줄거리").runtimeMinutes(95).build());
                probe.reset(path, change, first.getId(), second.getId());
                long room = history.create(owner.getId()).id();
                var seed = history.prepare(owner.getId(), room, "영화 추천해줘");
                history.complete(owner.getId(), room, seed, "영화 추천해줘", new ChatResponse("앞서 추천한 공개 작품이에요.", USAGE, true, List.of(first.getId(), second.getId())));
                String question = switch (path) {
                    case SINGLE_RATIONALE -> "첫 번째 추천 이유를 설명해줘";
                    case GROUP_RATIONALE -> "추천 이유를 모두 설명해줘";
                    case DETAIL -> "ID " + first.getId() + " 상세";
                    case COMPOUND_DETAIL -> "ID " + first.getId() + " 상세와 영화와 시리즈의 차이를 설명해줘";
                };
                var response = api.call("POST", "/api/chat/rooms/" + room + "/messages", token, JSON.writeValueAsString(Map.of("message", question)));
                int delta = history.history(owner.getId(), room, null).messages().size() - 2;
                int expectedStatus = change != Change.NONE && path != PathKind.DETAIL ? 502 : 200;
                var row = new LinkedHashMap<String, Object>(); observations.add(row);
                row.put("path", path.name()); row.put("change", change.name()); row.put("expectedStatus", expectedStatus);
                row.put("httpStatus", response.statusCode()); row.put("messageDelta", delta); row.put("osivBound", probe.requestBound);
                row.put("generationCalls", probe.generationCalls); row.put("status", "NOT_CHECKED");
                try {
                    assertTrue(probe.requestBound); assertEquals(change != Change.NONE, probe.mutated);
                    assertEquals(path == PathKind.DETAIL ? 0 : 1, probe.generationCalls);
                    assertEquals(expectedStatus, response.statusCode()); assertEquals(expectedStatus == 200 ? 2 : 0, delta);
                    if (expectedStatus == 502) {
                        assertTrue(response.body().contains("AI_INVALID_RESPONSE"));
                        assertFalse(response.body().contains("공개 원본 줄거리"));
                    } else {
                        String text = JSON.readTree(response.body()).get("messages").get(1).get("text").asString();
                        if (path == PathKind.DETAIL && change == Change.UPDATE) {
                            assertTrue(text.contains("변경된 공개 제목")); assertTrue(text.contains("변경된 공개 줄거리"));
                            assertTrue(text.contains("장르: comedy")); assertTrue(text.contains("러닝타임: 121분"));
                            assertFalse(text.contains("첫 공개 원본 줄거리"));
                        } else if (path == PathKind.DETAIL && change == Change.DELETE) assertTrue(text.contains("찾지 못했어요"));
                        else assertTrue(text.contains("첫 공개 원본 줄거리"));
                    }
                    // Confirm independent commit rather than a missing/no-op race injection.
                    try (var connection = dataSource.getConnection(); var query = connection.prepareStatement("select overview,runtime_minutes from contents where id=?")) {
                        query.setLong(1, probe.target); try (var rows = query.executeQuery()) {
                            if (change == Change.DELETE) assertFalse(rows.next());
                            else { assertTrue(rows.next()); if (change == Change.UPDATE) { assertEquals("변경된 공개 줄거리", rows.getString(1)); assertEquals(121, rows.getInt(2)); } }
                        }
                    }
                    row.put("status", "PASS");
                } catch (AssertionError e) { row.put("status", "EXPECTATION_MISMATCH"); }
                history.delete(owner.getId(), room);
                contents.deleteAllById(List.of(first.getId(), second.getId()));
            }
            assertEquals(12, observations.size());
            report.put("status", observations.stream().allMatch(row -> row.get("status").equals("PASS")) ? "PASS" : "FAIL");
        } finally {
            report.put("finishedAt", java.time.Instant.now().toString());
            Files.writeString(OUTPUT.resolve("report-" + java.time.Instant.now().toString().replaceAll("[^0-9]", "") + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        assertEquals("PASS", report.get("status"), "Inspect sanitized target/catalog-freshness report");
    }
    static final class Api {
        final int port; final HttpClient client = HttpClient.newHttpClient();
        Api(int port) { this.port = port; }
        HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            if (token != null) request.header("Authorization", "Bearer " + token);
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        String login(String email) throws Exception {
            var response = call("POST", "/login", null, JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD)));
            assertEquals(200, response.statusCode()); return JSON.readTree(response.body()).get("token").asString();
        }
    }
}
