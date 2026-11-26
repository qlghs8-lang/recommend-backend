package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.repository.CatalogSnapshotRepository;
import com.example.recommend.chat.service.*;
import com.example.recommend.repository.ContentRepository;
import com.openai.core.RequestOptions;
import com.openai.models.responses.ResponseCreateParams;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.*;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.recommend.chat.config.QualityBaselineTest.*;

/** Opt-in fixed twelve-case scopes: original classifier, retrieval, tools and final validation. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import(CatalogRetriever.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QualityBaselineLiveTest {
    static final Path REMEASUREMENT_OUT = Path.of("target/quality-remeasurement-20261125");

    static Path remeasurementOut() {
        if (Boolean.getBoolean("qualityBaselineRemaining")) return QualityRemainingPlan.OUT;
        return Boolean.getBoolean("qualityBaselineRemeasurementV2") ? QualityRemeasurementV2Plan.OUT : REMEASUREMENT_OUT;
    }

    static List<tools.jackson.databind.JsonNode> pilotCases() throws Exception {
        boolean remaining = Boolean.getBoolean("qualityBaselineRemaining");
        if (remaining && (Boolean.getBoolean("qualityBaselineRemeasurementV2") || Boolean.getBoolean("qualityBaselineRemeasurement")))
            throw new IllegalStateException("Choose exactly one fixed evaluation scope");
        return cases().subList(remaining ? 12 : 0, remaining ? 24 : 12);
    }

    static double validateRemeasurement(double ceiling) throws Exception {
        if (Boolean.getBoolean("qualityBaselineRemaining"))
            return QualityRemainingPlan.validate(Path.of(""), remeasurementOut().resolve("run-plan.json"), ceiling);
        if (Boolean.getBoolean("qualityBaselineRemeasurementV2"))
            return QualityRemeasurementV2Plan.validate(Path.of(""), remeasurementOut().resolve("run-plan.json"), ceiling);
        var plan = JSON.readTree(Files.readString(REMEASUREMENT_OUT.resolve("run-plan.json")));
        if (!"FIXED_D01_D12_REMEASUREMENT_V1".equals(plan.get("protocol").asString()))
            throw new IllegalStateException("Reviewed remeasurement plan required");
        double prior = plan.get("priorReservationEstimateUsd").asDouble();
        if (!Double.isFinite(prior) || prior < 0 || !Double.isFinite(ceiling) || ceiling <= 0
                || ceiling > .40 || ceiling + prior > 1)
            throw new IllegalStateException("Cumulative approved estimate exceeded");
        for (var input : plan.get("pinnedInputs")) {
            Path path = Path.of(input.get("path").asString()).normalize();
            if (path.isAbsolute() || path.startsWith("..") || !sha(path).equals(input.get("sha256").asString()))
                throw new IllegalStateException("Reviewed public input or runner changed");
        }
        try (var reports = Files.list(REMEASUREMENT_OUT)) {
            if (reports.anyMatch(p -> p.getFileName().toString().matches("pilot-[0-9]+\\.json")))
                throw new IllegalStateException("One remeasurement already exists; review before another run");
        }
        return prior;
    }
    @Autowired CatalogRetriever retriever;
    @Autowired ContentRepository contents;
    @Autowired CatalogSnapshotRepository snapshots;
    @Autowired EntityManager em;
    @Autowired DataSource dataSource;

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Dedicated evaluation environment is missing");
        return value;
    }
    @Test @EnabledIfSystemProperty(named = "qualityBaselineLive", matches = "true")
    void fixedTwelveTurnPilotWithExplicitPrivateEnvironment() throws Exception {
        var selectedCases = pilotCases();
        var rows = new ArrayList<Map<String, Object>>();
        for (var c : selectedCases) {
            var r = new LinkedHashMap<String, Object>(); r.put("caseId", c.get("id").asString());
            r.put("status", "NOT_RUN"); rows.add(r);
        }
        var report = new LinkedHashMap<String, Object>(); report.put("results", rows);
        report.put("mode", "LIVE_MODEL_PUBLIC_H2_PILOT_NOT_BROWSER_OR_PERSISTENCE");
        report.put("applicationWrites", 0); report.put("privateRowsRead", 0);
        report.put("pilotState", "SETUP_REQUIRED"); report.put("providerRequests", 0);
        boolean v2 = Boolean.getBoolean("qualityBaselineRemeasurementV2");
        boolean remaining = Boolean.getBoolean("qualityBaselineRemaining");
        boolean remeasurement = Boolean.getBoolean("qualityBaselineRemeasurement") || v2 || remaining;
        report.put("remeasurement", remeasurement && !remaining);
        if (v2) report.put("remeasurementProtocol", QualityRemeasurementV2Plan.PROTOCOL);
        if (remaining) {
            report.put("evaluationProtocol", QualityRemainingPlan.PROTOCOL);
            report.put("selectedCaseIds", QualityRemainingPlan.CASE_IDS);
            report.put("syntheticFixtureCase", "D24");
            report.put("syntheticFixtureSeeded", false);
            report.put("realPrivateRowsRead", 0);
        }
        var fixture = new QualityBaselineTest();
        fixture.dataSource = dataSource; fixture.contents = contents; fixture.em = em; fixture.retriever = retriever;
        QualityPilotBudget budget = null;
        var synthetic = new QualityRemainingFixture(dataSource);
        try {
            String model = required("CHAT_EVAL_MODEL");
            if (!model.equals("gpt-6-luna")) throw new IllegalStateException("Only the explicitly priced comparison model is permitted");
            double ceiling = Double.parseDouble(required("CHAT_EVAL_MAX_USD"));
            if (remeasurement) {
                report.put("priorReservationEstimateUsd", validateRemeasurement(ceiling));
                if (remaining) report.put("priorCompletedPilotSha256", "bef8e286be4c6c65028692b0b9a37b3c98d2ded4d578b6c7be7f39bfa6246b79");
                else report.put("comparisonBaselineSha256", "d32f339ab407bd7736705ea17dc429e8736925df1fb3dd5f00264204a731122c");
                report.put("productClientSha256", sha(Path.of("src/main/java/com/example/recommend/chat/client/OpenAiClient.java")));
                report.put("runPlanSha256", sha(remeasurementOut().resolve("run-plan.json")));
            }
            budget = new QualityPilotBudget(ceiling); final var reservation = budget;
            var p = new AiProperties(); p.setEnabled(true); p.setModel(model);
            p.setApiKey(required("CHAT_EVAL_OPENAI_API_KEY")); p.setMaxOutputTokens(2048); p.setTimeoutSeconds(20); p.validate();
            Path sourcePath = OUT.resolve("public-snapshot.json"); String before = sha(sourcePath);
            fixture.install(JSON.readTree(Files.readString(sourcePath)));
            report.put("snapshotFileSha256", before); report.put("model", model); report.put("maxUsd", ceiling);
            report.put("sharedTurnOutputTokens", 2048); report.put("turnTimeoutSeconds", 20);
            var sdk = AiConfig.createSdk(p, "https://api.openai.com/v1");
            try {
                var intercepted = mock(com.openai.client.OpenAIClient.class); var resource = spy(sdk.responses());
                when(intercepted.responses()).thenReturn(resource);
                doAnswer(call -> {
                    ResponseCreateParams params = call.getArgument(0);
                    if (remaining) QualityRemainingFixture.assertNoSyntheticAccountData(params.toString());
                    reservation.reserve(params.toString().length(), params.maxOutputTokens().orElseThrow());
                    var response = sdk.responses().create(params, (RequestOptions) call.getArgument(1));
                    response.usage().ifPresent(u -> { reservation.responsesWithUsage++;
                        reservation.inputTokens += u.inputTokens(); reservation.outputTokens += u.outputTokens(); });
                    return response;
                }).when(resource).create(any(ResponseCreateParams.class), any(RequestOptions.class));
                var delegate = new OpenAiClient(intercepted, p);
                var client = mock(AiClient.class, org.mockito.AdditionalAnswers.delegatesTo(delegate));
                // Empty isolated H2 reaction tables only; never query application accounts.
                var interactions = new ChatInteractionReader(em);
                var tools = new CatalogToolService(retriever, interactions);
                var recommendations = new CatalogRecommendations(retriever, client, snapshots, tools, interactions);
                var router = new ChatRouter(client, recommendations, interactions, new CatalogDetails(em), p);
                boolean stop = false;
                for (int i = 0; i < selectedCases.size(); i++) {
                    if (stop) continue;
                    var row = rows.get(i); String q = question(selectedCases.get(i)); reset(client);
                    long actor = 1;
                    if (remaining && row.get("caseId").equals("D24")) {
                        synthetic.install(); actor = QualityRemainingFixture.OWNER;
                        report.put("syntheticFixtureSeeded", true);
                        row.put("syntheticReactionFixture", synthetic.audit(em, retriever, snapshots));
                    }
                    row.put("status", "STARTED"); row.put("stage", "INTERPRETATION");
                    long start = System.nanoTime(); int initialCalls = budget.requests;
                    doAnswer(call -> {
                        long t = System.nanoTime();
                        var plan = delegate.classify(call.getArgument(0), call.getArgument(1));
                        row.put("classificationMs", (System.nanoTime() - t) / 1_000_000); row.put("actualPlan", plan);
                        return plan;
                    }).when(client).classify(anyList(), any());
                    doAnswer(call -> {
                        CatalogTools session = call.getArgument(1); row.put("stage", "SELECTION");
                        row.put("initialCandidateIds", ids(session.candidates()));
                        var events = new ArrayList<Map<String, Object>>(); row.put("toolEvents", events);
                        CatalogTools traced = new CatalogTools() {
                            public List<CatalogCandidate> candidates() { return session.candidates(); }
                            public List<RecommendationConstraint> constraints() { return session.constraints(); }
                            public Map<String,List<Long>> referenceScope() { return session.referenceScope(); }
                            public boolean privateDataRead() { return session.privateDataRead(); }
                            public String execute(String name, String args) {
                                long t = System.nanoTime(); String result = session.execute(name, args);
                                events.add(Map.of("name", name, "latencyMicros", (System.nanoTime() - t) / 1000,
                                        "seenCandidateIds", ids(session.candidates()))); return result;
                            }
                        };
                        try {
                            var selected = delegate.recommendWithTools(call.getArgument(0), traced, call.getArgument(2));
                            row.put("selectedBeforeValidation", selected.contentIds()); row.put("stage", "VALIDATION");
                            return selected;
                        } finally { row.put("finalCandidateIds", ids(session.candidates())); }
                    }).when(client).recommendWithTools(anyList(), any(), any());
                    try {
                        var reply = router.reply(actor, List.of(new AiMessage(AiMessage.Role.USER, q)));
                        row.put("answer", reply.message()); row.put("selectedIds", reply.contentIds()); row.put("usage", reply.usage());
                        row.put("status", reply.contentIds().isEmpty() ? "NO_RECOMMENDATION_REVIEW_REQUIRED" : "RETURNED_REVIEW_REQUIRED");
                        row.put("stage", "ANSWER");
                        if (remaining && row.get("caseId").equals("D24"))
                            row.put("syntheticPolicyReview", synthetic.policyReview(reply.contentIds()));
                    } catch (ChatException ex) {
                        row.put("status", ex.code());
                        if (Set.of(429, 503, 504).contains(ex.status()) || budget.limitReached) stop = true;
                    } catch (RuntimeException ex) {
                        row.put("status", "EVALUATION_ERROR"); row.put("errorType", ex.getClass().getSimpleName()); stop = true;
                    }
                    row.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
                    row.put("providerRequests", budget.requests - initialCalls);
                    if (!stop && i < selectedCases.size() - 1) Thread.sleep(1000);
                }
                report.put("pilotState", stop ? "STOPPED" : "EXECUTED_REQUIRES_REVIEW");
            } finally { sdk.close(); }
            assertEquals(before, sha(sourcePath));
        } finally {
            boolean cleaned = synthetic.cleanup();
            if (remaining) report.put("syntheticFixtureCleanupSucceeded", cleaned);
            fixture.cleanup();
            if (budget != null) {
                report.put("providerRequests", budget.requests); report.put("responsesWithUsage", budget.responsesWithUsage);
                report.put("inputTokens", budget.inputTokens); report.put("outputTokens", budget.outputTokens);
                report.put("reservedOutputTokens", budget.reservedOutput); report.put("requestCharacters", budget.chars);
                report.put("reservationEstimateUsd", QualityPilotBudget.reservedEstimate(budget.requests, budget.chars, budget.reservedOutput));
                report.put("observedUncachedListPriceEstimateUsd", budget.observedListPriceEstimate());
                report.put("allRequestsHaveUsage", budget.responsesWithUsage == budget.requests);
                report.put("billingTotalKnown", false); report.put("limitReached", budget.limitReached);
                if (remeasurement && report.containsKey("priorReservationEstimateUsd"))
                    report.put("cumulativeReservationEstimateUsd", (double) report.get("priorReservationEstimateUsd")
                            + QualityPilotBudget.reservedEstimate(budget.requests, budget.chars, budget.reservedOutput));
            }
            write(remeasurement ? remeasurementOut() : OUT, "pilot", report);
        }
        assertNotEquals("SETUP_REQUIRED", report.get("pilotState"));
        if (remaining) assertEquals(true, report.get("syntheticFixtureCleanupSucceeded"), "Synthetic cleanup failed; inspect isolated test state");
        assertEquals(0, rows.stream().filter(r -> "NOT_RUN".equals(r.get("status"))).count(), "Incomplete pilot; preserve report");
    }
}
