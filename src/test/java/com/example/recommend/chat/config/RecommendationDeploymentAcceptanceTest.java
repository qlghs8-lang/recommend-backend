package com.example.recommend.chat.config;

import com.example.recommend.RecommendApplication;
import com.example.recommend.chat.client.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.*;
import com.example.recommend.domain.*;
import com.example.recommend.repository.*;
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
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in. Application DB metadata is read-only; all fixture writes use a newly owned schema. */
@EnabledIfSystemProperty(named = "chatDeploymentAcceptance", matches = "true")
class RecommendationDeploymentAcceptanceTest {
    private static final Path OUTPUT = Path.of("target/deployment-acceptance");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String EMAIL = "deployment-fixture@example.invalid";
    private static final String PASSWORD = "Synthetic-Only-2026!"; // Disposable fixture, never an existing user's credential.
    private static final String INITIAL = "내 취향에 맞는 영화 추천해줘";
    private static final ChatResponse.Usage USAGE = new ChatResponse.Usage(1, 1, 2);

    @TestConfiguration(proxyBeanMethods = false) static class ScriptedProvider {
        @Bean @Primary AiClient deploymentClient() {
            return new AiClient() {
                @Override public ChatPlan classify(List<AiMessage> context, TurnBudget budget) {
                    String text = context.toString();
                    assertFalse(text.contains("PRIVATE_DEPLOYMENT_ANCHOR")); assertFalse(text.contains("장르 점수"));
                    budget.consume(USAGE);
                    String current = context.get(context.size() - 1).text();
                    if (current.equals(INITIAL)) return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.PERSONALIZED, ChatIntent.Media.ANY)), false,
                            List.of(new RecommendationConstraint(RecommendationConstraint.Kind.MEDIA, "MOVIE", "영화", List.of())));
                    return ChatPlan.single(new ChatIntent(ChatIntent.Route.RATIONALE, ChatIntent.Media.ANY, null, null, null,
                            1, current.contains("첫 번째") ? 1 : 0, null));
                }
                private CatalogRationale rationale(String original, String question, CatalogCandidate work) {
                    if (question.equals("실패 원자성 검사")) throw ChatException.timeout();
                    assertEquals(INITIAL, original); assertFalse(work.title().contains("PRIVATE_DEPLOYMENT_ANCHOR"));
                    return new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, work.genres())), "공개 장르를 대조했어요.");
                }
                @Override public CatalogRationale justify(String original, String question, CatalogCandidate work, TurnBudget budget) {
                    budget.consume(USAGE); return rationale(original, question, work);
                }
                @Override public Map<Long, CatalogRationale> justifyMany(String original, String question, List<CatalogCandidate> works, TurnBudget budget) {
                    budget.consume(USAGE); var reasons = new LinkedHashMap<Long, CatalogRationale>();
                    works.forEach(w -> reasons.put(w.id(), rationale(original, question, w))); return reasons;
                }
                @Override public ChatResponse reply(List<AiMessage> context) { throw new AssertionError("Unexpected generation branch"); }
            };
        }
    }

    /** Credentials stay inside the server-side process and are never included in reports or exceptions. */
    private static final class Database implements AutoCloseable {
        final Properties properties = new Properties();
        final String url, username, password, schema;
        final String scratch = "recommend_provenance_acceptance_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        boolean created;
        Database() throws Exception {
            Path execution = Path.of(System.getProperty("chatExecutionRoot", "C:/Users/jaehyeon/eclipse-workspace/recommend"));
            try (var reader = Files.newBufferedReader(execution.resolve("src/main/resources/application.properties"))) { properties.load(reader); }
            var env = new StandardEnvironment();
            url = env.resolveRequiredPlaceholders(properties.getProperty("spring.datasource.url"));
            username = env.resolveRequiredPlaceholders(properties.getProperty("spring.datasource.username"));
            password = env.resolveRequiredPlaceholders(properties.getProperty("spring.datasource.password"));
            var address = URI.create(url.substring(5));
            if (!url.startsWith("jdbc:mysql://") || !Set.of("127.0.0.1", "localhost").contains(address.getHost())
                    || address.getPort() != 3306) throw new IllegalStateException("Only the configured local MySQL is permitted");
            schema = address.getPath().substring(1);
            if (!schema.matches("[a-zA-Z0-9_]+")) throw new IllegalStateException("Unexpected application schema");
        }
        Connection application() throws SQLException { return DriverManager.getConnection(url, username, password); }
        String scratchUrl() { return "jdbc:mysql://127.0.0.1:3306/" + scratch + "?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"; }
        Map<String, Object> inspect() throws SQLException {
            try (var c = application()) {
                c.setReadOnly(true);
                var columns = new LinkedHashMap<String, Object>();
                try (var q = c.prepareStatement("select column_name,data_type,is_nullable from information_schema.columns where table_schema=database() and table_name='chat_messages' and column_name in ('text','content_references','recommendation_provenance') order by column_name"); var rows = q.executeQuery()) {
                    while (rows.next()) columns.put(rows.getString(1), Map.of("type", rows.getString(2), "nullable", rows.getString(3)));
                }
                return columns;
            }
        }
        void allocate() throws SQLException {
            if (!scratch.matches("recommend_provenance_acceptance_[0-9a-f]{12}") || scratch.equals(schema)) throw new IllegalStateException("Unsafe test schema");
            try (var c = application(); var q = c.prepareStatement("select count(*) from information_schema.schemata where schema_name=?")) {
                q.setString(1, scratch); try (var rows = q.executeQuery()) { rows.next(); if (rows.getInt(1) != 0) throw new IllegalStateException("Scratch schema already exists"); }
                try (var ddl = c.createStatement()) { ddl.execute("create database `" + scratch + "` character set utf8mb4"); created = true; }
            }
        }
        void migrationProbe() throws SQLException {
            try (var c = DriverManager.getConnection(scratchUrl(), username, password); var q = c.createStatement()) {
                q.execute("create table chat_messages(id bigint primary key, text longtext not null)");
                q.execute("insert into chat_messages values(1,'synthetic legacy row')");
                q.execute("alter table chat_messages add column recommendation_provenance LONGTEXT NULL");
                try (var rows = q.executeQuery("select count(*) from chat_messages where id=1 and text='synthetic legacy row' and recommendation_provenance is null")) {
                    rows.next(); assertEquals(1, rows.getInt(1));
                }
            }
        }
        @Override public void close() throws SQLException {
            if (created && scratch.matches("recommend_provenance_acceptance_[0-9a-f]{12}") && !scratch.equals(schema))
                try (var c = application(); var q = c.createStatement()) { q.execute("drop database `" + scratch + "`"); }
        }
        @Override public String toString() { return "Database[server-only]"; }
    }

    @Test void applicationSchemaReadOnly() throws Exception {
        Files.createDirectories(OUTPUT);
        try (var db = new Database()) {
            var columns = db.inspect(); var scratchCounts = new LinkedHashMap<String, Integer>();
            for (String name : List.of("deployment-initial-failed.json", "browser-deployment-report.json")) {
                Path report = OUTPUT.resolve(name); if (!Files.exists(report)) continue;
                String scratch = JSON.readTree(Files.readString(report)).get("scratchSchema").asString();
                if (!scratch.matches("recommend_provenance_acceptance_[0-9a-f]{12}") || scratch.equals(db.schema))
                    throw new IllegalStateException("Unexpected recorded scratch schema");
                try (var c = db.application(); var q = c.prepareStatement("select count(*) from information_schema.schemata where schema_name=?")) {
                    c.setReadOnly(true); q.setString(1, scratch);
                    try (var rows = q.executeQuery()) { rows.next(); scratchCounts.put(scratch, rows.getInt(1)); }
                }
            }
            write("application-schema.json", Map.of("columns", columns, "applicationRowsRead", false,
                    "applicationWrites", false, "recordedScratchSchemaCounts", scratchCounts));
            assertEquals(Map.of("type", "longtext", "nullable", "YES"), columns.get("recommendation_provenance"));
            assertTrue(scratchCounts.values().stream().allMatch(n -> n == 0), "Owned scratch schema cleanup incomplete");
        }
        catch (SQLException e) { throw new IllegalStateException("Local schema inspection failed; driver details withheld"); }
    }

    @Test @EnabledIfSystemProperty(named = "chatApplyProvenanceColumn", matches = "true")
    void applyOnlyMissingNullableApplicationColumn() throws Exception {
        Files.createDirectories(OUTPUT);
        var report = new LinkedHashMap<String, Object>(); report.put("applicationRowsRead", false);
        report.put("applicationRowWrites", false);
        try (var db = new Database()) {
            var before = db.inspect(); report.put("before", before);
            assertTrue(before.containsKey("text") && before.containsKey("content_references"), "Expected existing chat schema missing");
            if (!before.containsKey("recommendation_provenance")) {
                try (var c = db.application(); var q = c.createStatement()) {
                    q.setQueryTimeout(8); q.execute("set session lock_wait_timeout=5");
                    // No table-copy fallback, no backfill, and no alteration of an existing column.
                    q.execute("alter table chat_messages add column recommendation_provenance LONGTEXT NULL, ALGORITHM=INSTANT");
                }
                report.put("columnAdded", true);
            } else report.put("columnAdded", false);
            var after = db.inspect(); report.put("after", after);
            assertEquals(Map.of("type", "longtext", "nullable", "YES"), after.get("recommendation_provenance"));
            assertEquals(before.get("text"), after.get("text")); assertEquals(before.get("content_references"), after.get("content_references"));
            report.put("status", "PASS");
        } catch (SQLException e) { report.put("status", "DDL_FAILED_WITHOUT_FALLBACK"); throw new IllegalStateException("Nullable instant DDL failed; no fallback attempted and driver details withheld");
        } finally { write("application-column-migration.json", report); }
    }

    @Test void actualMySqlJwtHttpAndOptionalBrowser() throws Exception {
        Files.createDirectories(OUTPUT);
        var report = new LinkedHashMap<String, Object>(); report.put("provider", "scripted; no paid calls");
        report.put("contractStatus", "NOT_RUN");
        report.put("applicationWrites", false); report.put("applicationRowsRead", false);
        boolean browser = Boolean.getBoolean("chatDeploymentBrowser");
        Path ready = OUTPUT.resolve("browser-ready.json"), finished = OUTPUT.resolve("browser-complete.json");
        if (browser && (Files.exists(ready) || Files.exists(finished))) throw new IllegalStateException("Browser handshake already exists; archive the previous run first");
        try (var db = new Database()) {
            report.put("applicationColumnsBefore", db.inspect()); db.allocate(); report.put("scratchSchema", db.scratch);
            db.migrationProbe(); report.put("additiveMigrationPreservesLegacyRow", "PASS");
            var overrides = new LinkedHashMap<String, Object>();
            overrides.put("server.port", "0"); overrides.put("server.address", "127.0.0.1");
            overrides.put("spring.datasource.url", db.scratchUrl()); overrides.put("spring.datasource.username", db.username);
            overrides.put("spring.datasource.password", db.password); overrides.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
            overrides.put("spring.jpa.hibernate.ddl-auto", "create"); overrides.put("spring.jpa.database-platform", "org.hibernate.dialect.MySQLDialect");
            overrides.put("spring.jpa.show-sql", "false"); overrides.put("spring.main.banner-mode", "off");
            overrides.put("JWT_SECRET", "synthetic-deployment-test-only-key-never-used-for-existing-accounts");
            overrides.put("spring.security.user.password", PASSWORD); // Suppress an unused generated development password in logs.
            overrides.put("app.ai.enabled", "true"); overrides.put("app.ai.api-key", "synthetic-no-network"); overrides.put("app.ai.model", "synthetic");
            overrides.put("app.ai.requests-per-minute", "60"); overrides.put("app.ai.global-requests-per-minute", "600");
            overrides.put("app.phone.store", "memory"); overrides.put("app.upload-dir", OUTPUT.resolve("uploads").toAbsolutePath().toString());
            var env = new StandardServletEnvironment(); env.getPropertySources().addFirst(new MapPropertySource("isolatedAcceptance", overrides));
            try (var app = new SpringApplicationBuilder(RecommendApplication.class, ScriptedProvider.class).environment(env).run()) {
                assertEquals(db.scratchUrl(), app.getEnvironment().getProperty("spring.datasource.url"));
                int port = app.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
                var users = app.getBean(UserRepository.class); var contents = app.getBean(ContentRepository.class);
                var events = app.getBean(UserContentInteractionRepository.class); var history = app.getBean(ChatHistoryStore.class);
                var encoder = app.getBean(PasswordEncoder.class);
                var owner = users.saveAndFlush(User.builder().email(EMAIL).nickname("배포검사").password(encoder.encode(PASSWORD)).onboardingDone(true).build());
                users.saveAndFlush(User.builder().email("other-deployment@example.invalid").nickname("다른합성계정").password(encoder.encode(PASSWORD)).onboardingDone(true).build());
                var anchor = contents.saveAndFlush(Content.builder().title("PRIVATE_DEPLOYMENT_ANCHOR").type("TV").genres("Science Fiction").build());
                for (String[] work : List.of(new String[]{"별의 항로", "Science Fiction"}, new String[]{"궤도의 편지", "Science Fiction,Drama"}, new String[]{"웃음의 하루", "Comedy"}))
                    contents.saveAndFlush(Content.builder().title(work[0]).type("MOVIE").genres(work[1]).overview("합성 공개 줄거리").rating(work[1].equals("Comedy") ? 9.0 : 8.0).build());
                events.saveAndFlush(UserContentInteraction.builder().user(owner).content(anchor).eventType(InteractionType.LIKE).createdAt(LocalDateTime.now()).build());
                var http = new Api(port); assertEquals(401, http.call("GET", "/api/chat/rooms", null, null).statusCode());
                String token = http.login(EMAIL), other = http.login("other-deployment@example.invalid");
                var create = http.call("POST", "/api/chat/rooms", token, ""); assertEquals(201, create.statusCode());
                long room = JSON.readTree(create.body()).get("id").asLong(); String path = "/api/chat/rooms/" + room + "/messages";
                var first = http.turn(path, token, INITIAL); String firstText = first.get("messages").get(1).get("text").asString();
                assertTrue(firstText.contains("장르 점수 3"));
                var group = http.turn(path, token, "이 영화들을 추천한 이유는 뭐야?"); assertTrue(group.get("messages").get(1).get("text").asString().contains("최초 추천 당시"));
                var repeat = http.turn(path, token, "방금 이유를 설명한 첫 번째 작품은 왜 추천했어?");
                assertTrue(repeat.get("messages").get(1).get("text").asString().contains("장르 점수 3"));
                assertEquals(404, http.call("GET", path, other, null).statusCode()); assertEquals(404, http.call("POST", path, other, JSON.writeValueAsString(Map.of("message", INITIAL))).statusCode());
                int count = history.history(owner.getId(), room, null).messages().size();
                assertEquals(504, http.call("POST", path, token, JSON.writeValueAsString(Map.of("message", "실패 원자성 검사"))).statusCode());
                assertEquals(count, history.history(owner.getId(), room, null).messages().size());
                // LIKE is an upsert in this app; DISLIKE explicitly replaces it.
                assertEquals(200, http.call("POST", "/api/interactions/" + anchor.getId() + "/dislike", token, "").statusCode());
                var afterRemoval = http.turn(path, token, "방금 이유를 설명한 첫 번째 작품은 왜 추천했어?");
                assertTrue(afterRemoval.get("messages").get(1).get("text").asString().contains("장르 점수 3"));
                var fresh = http.turn(path, token, INITIAL); assertTrue(fresh.get("messages").get(1).get("text").asString().contains("기록이 부족"));
                var reload = http.call("GET", path, token, null); assertEquals(200, reload.statusCode());
                assertEquals(firstText, JSON.readTree(reload.body()).get("messages").get(1).get("text").asString());
                var snapshot = history.prepare(owner.getId(), room, "다음 질문");
                assertTrue(snapshot.context().stream().noneMatch(m -> m.text().contains("장르 점수")));
                assertTrue(snapshot.provenance().values().stream().anyMatch(p -> p.request().equals(INITIAL)));
                try (var c = app.getBean(javax.sql.DataSource.class).getConnection(); var q = c.createStatement(); var rows = q.executeQuery("select data_type,is_nullable from information_schema.columns where table_schema=database() and table_name='chat_messages' and column_name='recommendation_provenance'")) {
                    assertTrue(rows.next()); assertEquals("longtext", rows.getString(1)); assertEquals("YES", rows.getString(2));
                }
                // Restore only the disposable owner's fixture for the independent browser flow.
                http.call("POST", "/api/interactions/" + anchor.getId() + "/like", token, "");
                report.put("mysqlHttpContracts", "PASS"); report.put("httpStoredTurns", 5); report.put("unauthenticated401", true);
                report.put("foreignRoom404", true); report.put("timeoutAtomicity", true); report.put("privateMetadataJsonExcluded", true);
                if (browser) {
                    write("browser-ready.json", Map.of("port", port, "email", EMAIL, "anchorId", anchor.getId(), "schema", db.scratch));
                    Instant deadline = Instant.now().plusSeconds(900);
                    while (!Files.exists(finished) && Instant.now().isBefore(deadline)) Thread.sleep(250);
                    assertTrue(Files.exists(finished), "Browser completion was not recorded within the bounded window");
                    var observed = JSON.readTree(Files.readString(finished)); assertTrue(observed.get("passed").asBoolean());
                    report.put("browserObservations", observed);
                } else report.put("browserObservations", "NOT_RUN");
                report.put("applicationColumnsAfter", db.inspect());
                assertEquals(report.get("applicationColumnsBefore"), report.get("applicationColumnsAfter"));
                report.put("contractStatus", "PASS");
            }
        } catch (SQLException e) { report.put("contractStatus", "DATABASE_FAILURE"); throw new IllegalStateException("Isolated deployment DB check failed; driver details withheld");
        } catch (AssertionError e) { report.put("contractStatus", "EXPECTATION_MISMATCH"); throw e;
        } finally { write("deployment-report.json", report); }
    }

    private static final class Api {
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
            var response = call("POST", path, token, JSON.writeValueAsString(Map.of("message", question))); assertEquals(200, response.statusCode());
            assertFalse(response.body().contains("positiveHistory")); assertFalse(response.body().contains("positiveGenres")); assertFalse(response.body().contains("provenance"));
            var json = JSON.readTree(response.body()); assertEquals(2, json.get("messages").size()); return json;
        }
    }
    private static void write(String name, Object value) throws Exception {
        Files.writeString(OUTPUT.resolve(name), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
