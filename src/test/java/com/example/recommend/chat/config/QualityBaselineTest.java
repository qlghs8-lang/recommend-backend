package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.*;
import com.example.recommend.domain.Content;
import com.example.recommend.repository.ContentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Evaluation-only: actual retrieval code on public H2 replay, never model-quality scores. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import(CatalogRetriever.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QualityBaselineTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path OUT = Path.of("target/quality-baseline-20261125");
    static final Path DEV = Path.of("docs/evaluation/quality-development-v1.json");
    static final Path PROBES = Path.of("src/test/resources/chat/quality-baseline-probes-v1.json");
    @Autowired CatalogRetriever retriever;
    @Autowired ContentRepository contents;
    @Autowired EntityManager em;
    @Autowired DataSource dataSource;
    final List<Long> installed = new ArrayList<>();

    static String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
    static List<JsonNode> cases() throws Exception {
        return JSON.readTree(Files.readString(DEV)).get("cases").valueStream().limit(24).toList();
    }
    static List<JsonNode> probes() throws Exception {
        return JSON.readTree(Files.readString(PROBES)).get("probes").valueStream().toList();
    }
    static String question(JsonNode c) { return c.get("turns").get(0).get("user").asString(); }
    static RecommendationCriteria criteria(JsonNode spec, String question) {
        var cs = spec.get("constraints").valueStream().map(RecommendationConstraint::parse).toList();
        return RecommendationCriteria.from(cs, List.of(new AiMessage(AiMessage.Role.USER, question)), question);
    }
    static Object nullable(JsonNode row, String field) {
        var n = row.get(field);
        if (n == null || n.isNull()) return null;
        return switch (field) {
            case "id", "view_count" -> n.asLong();
            case "rating" -> n.asDouble();
            case "runtime_minutes", "episode_runtime_minutes" -> n.asInt();
            default -> n.asString();
        };
    }
    void install(JsonNode snapshot) throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"), "H2 only; no application writes");
            try (var query = connection.prepareStatement("insert into contents(id,type,title,overview,genres,release_date,rating,view_count,runtime_minutes,episode_runtime_minutes) values(?,?,?,?,?,?,?,?,?,?)")) {
                for (var row : snapshot.get("rows")) {
                    int i = 1;
                    for (String f : List.of("id", "type", "title", "overview", "genres", "release_date", "rating", "view_count", "runtime_minutes", "episode_runtime_minutes"))
                        query.setObject(i++, nullable(row, f));
                    query.addBatch(); installed.add(row.get("id").asLong());
                }
                assertEquals(installed.size(), query.executeBatch().length);
            }
        }
    }
    @AfterEach void cleanup() { if (!installed.isEmpty()) contents.deleteAllById(installed); }
    static List<Long> ids(List<CatalogCandidate> candidates) { return candidates.stream().map(CatalogCandidate::id).toList(); }
    List<CatalogCandidate> matching(RecommendationCriteria criteria) {
        var cb = em.getCriteriaBuilder(); var q = cb.createQuery(Content.class); var c = q.from(Content.class);
        q.select(c).where(criteria.predicates(cb, c).toArray(jakarta.persistence.criteria.Predicate[]::new));
        return em.createQuery(q).setMaxResults(5001).getResultList().stream().map(CatalogCandidate::from).toList();
    }
    static Map<String, Object> measure(Supplier<List<CatalogCandidate>> query) {
        var times = new ArrayList<Long>(); List<CatalogCandidate> result = null;
        boolean stable = true;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime(); var next = query.get(); times.add((System.nanoTime() - start) / 1000);
            if (result != null && !ids(result).equals(ids(next))) stable = false;
            result = next;
        }
        assertNotNull(result); assertTrue(result.size() <= 12);
        var row = new LinkedHashMap<String, Object>(); row.put("candidateIds", ids(result));
        row.put("latencyMicros", times); row.put("stableAcrossThreeQueries", stable);
        return row;
    }
    static void write(String prefix, Map<String, Object> report) throws Exception {
        write(OUT, prefix, report);
    }
    static void write(Path outputDirectory, String prefix, Map<String, Object> report) throws Exception {
        Files.createDirectories(outputDirectory);
        var output = new LinkedHashMap<>(report);
        output.put("finishedAtUtc", Instant.now().toString()); output.put("developmentCorpusSha256", sha(DEV));
        output.put("probeSha256", sha(PROBES));
        output.put("semanticQuality", "UNJUDGED_REQUIRES_INDEPENDENT_HUMAN_LABELS");
        output.put("fullPipelineQuality", "NOT_MEASURED");
        Files.writeString(outputDirectory.resolve(prefix + "-" + Instant.now().toString().replaceAll("[^0-9]", "") + ".json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    }

    @Test void probeSpansAndFrozenDevelopmentCorpusAreValidWithoutReadingValidationQuestions() throws Exception {
        var spec = JSON.readTree(Files.readString(PROBES));
        assertEquals(spec.get("developmentCorpusSha256").asString(), sha(DEV));
        assertEquals(24, probes().size()); assertEquals(24, cases().size());
        for (int i = 0; i < 24; i++) {
            var c = cases().get(i); var p = probes().get(i);
            assertEquals(c.get("id").asString(), p.get("caseId").asString());
            criteria(p, question(c));
        }
    }
    @Test void twoIndependentRecommendationsAreOutsideCurrentProductContract() {
        var rec = new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY);
        assertThrows(ChatException.class, () -> new ChatPlan(List.of(rec, rec), false));
    }
    @Test void timingAndEmptyResultsAreNotConvertedIntoQualityPasses() {
        var result = measure(List::of);
        assertEquals(List.of(), result.get("candidateIds")); assertFalse(result.containsKey("qualityPass"));
        assertEquals(3, ((List<?>) result.get("latencyMicros")).size());
    }
    @Test void lateTopicEvidenceIsDifferentFromVisibleEvidence() {
        var c = new RecommendationConstraint(RecommendationConstraint.Kind.TOPIC, "가족", "가족", List.of("가족"));
        var criteria = new RecommendationCriteria(List.of(c), Set.of(), Set.of(), false);
        var content = Content.builder().id(1L).title("합성").type("TV").overview("가".repeat(401) + " 가족").build();
        var clipped = CatalogCandidate.from(content);
        var full = new CatalogCandidate(1L, "합성", "TV", "", "", content.getOverview());
        assertFalse(criteria.matches(clipped)); assertTrue(criteria.matches(full));
    }

    @Test @EnabledIfSystemProperty(named = "qualityPublicBaseline", matches = "true")
    void actualPublicSnapshotThroughExistingRetrieverWithoutClassifierOrSelection() throws Exception {
        Path snapshotPath = OUT.resolve("public-snapshot.json");
        var snapshot = JSON.readTree(Files.readString(snapshotPath));
        assertTrue(snapshot.get("repeated_snapshot_match").asBoolean());
        assertEquals(0, snapshot.get("application_writes").asInt());
        assertTrue(snapshot.get("rows").size() <= 5000);
        install(snapshot);
        String before = sha(snapshotPath);
        // One explicit warm-up; timings below are H2 retrieval only, not HTTP/model latency.
        retriever.retrieve(List.of(new AiMessage(AiMessage.Role.USER, "영화 추천")));
        var rows = new ArrayList<Map<String, Object>>();
        var seeds = JSON.readTree(Files.readString(Path.of("docs/evaluation/quality-evidence-seeds-v1.json"))).get("seeds");
        var seedMap = new HashMap<String, JsonNode>(); for (var s : seeds) seedMap.put(s.get("id").asString(), s);
        for (int i = 0; i < 24; i++) {
            var c = cases().get(i); var p = probes().get(i); String q = question(c);
            var context = List.of(new AiMessage(AiMessage.Role.USER, q));
            var row = new LinkedHashMap<String, Object>(); rows.add(row);
            row.put("caseId", c.get("id").asString()); row.put("probeStatus", p.get("status").asString());
            row.put("rawQuestionRetrieval", measure(() -> retriever.retrieve(context)));
            var criteria = criteria(p, q);
            if (criteria.clarification() != null) row.put("controlledGuidance", criteria.clarification());
            if (p.get("status").asString().equals("CONTROLLED_RETRIEVAL")) {
                var eligible = matching(criteria);
                var controlled = measure(() -> retriever.retrieve(context, criteria));
                row.put("controlledRetrieval", controlled);
                row.put("sqlEligibleIds", ids(eligible));
                row.put("sqlVsJavaEligibilityDisagreements", eligible.stream().filter(w -> !criteria.matches(w)).map(CatalogCandidate::id).toList());
                row.put("candidateValidationPassed", retriever.retrieve(context, criteria).stream().allMatch(criteria::matches));
                var evidence = p.get("developmentEvidenceSeed");
                if (evidence != null && !evidence.isNull()) {
                    var seed = seedMap.get(evidence.asString()); long id = seed.get("content_id").asLong();
                    var work = retriever.detail(id).orElseThrow();
                    assertTrue(work.overview().contains(seed.get("quote").asString()));
                    boolean inPool = ((List<?>) controlled.get("candidateIds")).contains(id);
                    boolean fits = criteria.matches(work);
                    row.put("provisionalWitnessId", id); row.put("witnessSurvivesLiteralCriteria", fits);
                    row.put("witnessInInitialCandidates", inPool);
                    row.put("witnessDiagnostic", inPool ? "VISIBLE_TO_SELECTION" : fits ? "RETRIEVAL_RANK" : "RETRIEVAL_FILTER");
                }
            }
        }
        var late = retriever.detail(406).orElse(null);
        var clipping = new LinkedHashMap<String, Object>();
        if (late != null) {
            var source = snapshot.get("rows").valueStream().filter(r -> r.get("id").asLong() == 406).findFirst().orElseThrow();
            var criteria = new RecommendationCriteria(List.of(new RecommendationConstraint(RecommendationConstraint.Kind.TOPIC,
                    "가족", "가족", List.of("가족"))), Set.of(), Set.of(), false);
            var full = new CatalogCandidate(late.id(), late.title(), late.type(), late.genres(), late.releaseDate(), source.get("overview").asString());
            clipping.put("contentId", 406); clipping.put("visibleMatchesFamily", criteria.matches(late));
            clipping.put("fullPlotMatchesFamily", criteria.matches(full));
            clipping.put("diagnostic", "CONTEXT_TRUNCATION_LITERAL_EVIDENCE_ONLY_NOT_SEMANTIC_VERDICT");
        }
        assertEquals(before, sha(snapshotPath));
        write("retrieval", Map.of("mode", "PUBLIC_H2_REPLAY_NO_MODEL", "snapshotFileSha256", before,
                "publicCatalogRows", installed.size(), "providerRequests", 0, "applicationWrites", 0,
                "timingScope", "three warm H2 queries per mode; excludes classifier, model, HTTP and browser",
                "contextBoundaryProbe", clipping, "results", rows));
    }

    @Test @EnabledIfSystemProperty(named = "qualitySchemaReplay", matches = "true")
    void reconstructRecordedPilotCriteriaAndPublicSchemasWithoutProviderCalls() throws Exception {
        Path replayOutput = Boolean.getBoolean("qualityBaselineRemeasurement") || Boolean.getBoolean("qualityBaselineRemeasurementV2") || Boolean.getBoolean("qualityBaselineRemaining")
                ? QualityBaselineLiveTest.remeasurementOut() : OUT;
        Files.createDirectories(replayOutput);
        Path pilotPath = Path.of(Objects.requireNonNull(System.getProperty("qualityPilotReport")));
        var pilot = JSON.readTree(Files.readString(pilotPath));
        assertEquals(sha(OUT.resolve("public-snapshot.json")), pilot.get("snapshotFileSha256").asString());
        install(JSON.readTree(Files.readString(OUT.resolve("public-snapshot.json"))));
        var method = OpenAiClient.class.getDeclaredMethod("recommendationFormat", List.class, List.class);
        method.setAccessible(true);
        var result = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 12; i++) {
            var recorded = pilot.get("results").get(i);
            if (!recorded.has("actualPlan")) continue;
            String caseId = recorded.get("caseId").asString();
            String q = question(cases().stream().filter(c -> c.get("id").asString().equals(caseId)).findFirst().orElseThrow());
            var plan = ChatPlan.parse(recorded.get("actualPlan").toString());
            var criteria = RecommendationCriteria.from(plan.constraints(), List.of(new AiMessage(AiMessage.Role.USER, q)), q);
            var row = new LinkedHashMap<String, Object>(); result.add(row); row.put("caseId", recorded.get("caseId").asString());
            row.put("recordedStatus", recorded.get("status").asString());
            row.put("guidanceBeforeRetrieval", criteria.clarification() == null ? "NONE" : criteria.clarification());
            var eligible = matching(criteria); row.put("literalEligibleIds", ids(eligible));
            if (recorded.has("initialCandidateIds")) {
                var candidates = new ArrayList<CatalogCandidate>();
                for (var id : recorded.get("initialCandidateIds")) candidates.add(retriever.detail(id.asLong()).orElseThrow());
                var config = (com.openai.models.responses.ResponseTextConfig) method.invoke(null, candidates, plan.constraints());
                var schema = new LinkedHashMap<String, Object>();
                config.format().orElseThrow().jsonSchema().orElseThrow().schema()._additionalProperties()
                        .forEach((k, v) -> schema.put(k, v.convert(Object.class)));
                var path = replayOutput.resolve("schema-" + recorded.get("caseId").asString() + ".json");
                Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(schema));
                row.put("reconstructedSchemaSha256", sha(path));
                row.put("schemaAcceptance", "NOT_TESTED_NO_EXTERNAL_CALL");
            }
        }
        write(replayOutput, "recorded-plan-replay", Map.of("mode", "RECORDED_LIVE_PLANS_PUBLIC_H2_NO_NEW_PROVIDER_CALLS",
                "pilotSha256", sha(pilotPath), "providerRequests", 0, "results", result));
    }
}
