package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.domain.Content;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.errors.*;
import com.openai.models.responses.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.recommend.chat.config.SchemaD10DiagnosisTest.*;

/** Two corrected public schemas only; HTTP acceptance is not recommendation-quality acceptance. */
class QuoteSchemaAcceptanceTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path INPUT = Path.of("src/test/resources/chat/quote-schema-acceptance-v1.json");
    static final Path OUT = Path.of("target/quote-schema-acceptance-20261125");
    static final String INPUT_PIN = "0248cd5fc291087b1841681eef4e3194362c42b4a22f7d18f01afeb16840258f";
    static final int MAX_REQUESTS = 2, MAX_REQUEST_CHARS = 50_000, MAX_TOTAL_CHARS = 100_000, OUTPUT = 128;
    static final double MAX_USD = .05;
    record Probe(String id, Map<String, Object> schema, String fileSha256) { }

    static String fileSha(Path path) throws Exception { return sha(Files.readAllBytes(path)); }
    static Map<String, String> priorPins() {
        return Map.of(
                "target/quality-baseline-20261125/pilot-20261125082720913747800.json", "d32f339ab407bd7736705ea17dc429e8736925df1fb3dd5f00264204a731122c",
                "target/schema-d10-diagnosis-20261125/live-20261125084417230961500.json", "eddbf74bdbfafe540f44a29e97d0edcbd61ff84faafbe933c644b0a0a174151f",
                "target/schema-d10-diagnosis-20261125/live-20261125091022637208400.json", "2fc2315aca164710a8a5c2e9443b593f4f22faab5a2aee4c65b08a570f3162d8",
                "target/quality-remeasurement-20261125/pilot-20261125091500585131800.json", "ed69c1e659941ac2fd397a14b5aa44c5462f34f83424ac800a9a90c678f797ec",
                "target/schema-d09-diagnosis-20261125/live-20261125122159875469300.json", "65690b6dc886bf948a3b99acac34c6976fb3476214a8d698f63f788e4a55bbc6");
    }
    static String optionalText(JsonNode node, String field) { return node.get(field).isNull() ? null : node.get(field).asString(); }
    static Integer optionalInt(JsonNode node, String field) { return node.get(field).isNull() ? null : node.get(field).asInt(); }
    static List<Probe> probes() throws Exception {
        assertEquals(INPUT_PIN, fileSha(INPUT));
        var probes = new ArrayList<Probe>();
        for (var row : JSON.readTree(Files.readAllBytes(INPUT)).get("cases")) {
            var works = new ArrayList<CatalogCandidate>();
            for (var candidate : row.get("candidates")) {
                String date = optionalText(candidate, "release_date");
                works.add(CatalogCandidate.from(Content.builder().id(candidate.get("id").asLong())
                        .title(candidate.get("title").asString()).type(candidate.get("type").asString())
                        .genres(optionalText(candidate, "genres")).overview(optionalText(candidate, "overview"))
                        .releaseDate(date == null ? null : LocalDate.parse(date))
                        .runtimeMinutes(optionalInt(candidate, "runtime_minutes"))
                        .episodeRuntimeMinutes(optionalInt(candidate, "episode_runtime_minutes")).build()));
            }
            var current = RecommendationQuoteSchemaTest.schema(works, ChatPlan.parse(row.get("actualPlan").toString()).constraints());
            Path fixture = Path.of(row.get("schemaPath").asString());
            assertEquals(row.get("schemaSha256").asString(), fileSha(fixture));
            assertEquals(JSON.readTree(Files.readAllBytes(fixture)), current, "Current product must reproduce the reviewed schema");
            var variants = current.get("properties").get("recommendations").get("items").get("anyOf");
            assertEquals(works.stream().map(CatalogCandidate::id).toList(),
                    java.util.stream.StreamSupport.stream(variants.spliterator(), false)
                            .map(v -> v.get("properties").get("contentId").get("enum").get(0).asLong()).toList());
            for (int i = 0; i < works.size(); i++) {
                var work = works.get(i);
                for (var variant : variants.get(i).get("properties").get("evidence").get("items").get("anyOf")) {
                    var fields = variant.get("properties");
                    var field = CatalogRationale.Field.valueOf(fields.get("field").get("enum").get(0).asString());
                    for (var value : fields.get("quote").get("enum")) {
                        String quote = value.asString();
                        assertTrue(quote.length() <= 40 && !quote.isBlank());
                        assertFalse(quote.codePoints().anyMatch(cp -> cp == '"' || cp == '\\' || Character.isISOControl(cp)
                                || cp == 0x2028 || cp == 0x2029 || cp >= 0xd800 && cp <= 0xdfff));
                        new CatalogRationale(List.of(new CatalogRationale.Evidence(field, quote)), "원문 검증").validate(work);
                    }
                }
            }
            probes.add(new Probe(row.get("caseId").asString(), read(Files.readAllBytes(fixture)), fileSha(fixture)));
        }
        assertEquals(List.of("D09", "D10"), probes.stream().map(Probe::id).toList());
        return probes;
    }
    static ResponseCreateParams params(String model, Probe probe) {
        var schema = ResponseFormatTextJsonSchemaConfig.Schema.builder();
        probe.schema().forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
        var format = ResponseTextConfig.builder().format(ResponseFormatTextJsonSchemaConfig.builder()
                .name("catalog_recommendations").strict(true).schema(schema.build()).build()).build();
        return ResponseCreateParams.builder().model(model).store(false).maxOutputTokens(OUTPUT)
                .input("Return one JSON object permitted by the supplied schema. Prefer an empty recommendations array. No commentary.")
                .text(format).build();
    }
    static void reserve(QualityPilotBudget budget, int chars) {
        if (budget.requests >= MAX_REQUESTS || chars > MAX_REQUEST_CHARS || budget.chars + chars > MAX_TOTAL_CHARS)
            throw new QualityPilotBudget.LimitReached();
        budget.reserve(chars, OUTPUT);
    }
    static void validateCaps(double prior, double cap) {
        if (!Double.isFinite(prior) || prior < 0 || !Double.isFinite(cap) || cap <= 0 || cap > MAX_USD || prior + cap > 1)
            throw new IllegalStateException("Fixed acceptance or cumulative estimate exceeded");
    }
    static double validatePlan(Path planPath, double cap) throws Exception {
        var plan = JSON.readTree(Files.readAllBytes(planPath));
        if (!"PRODUCT_D09_D10_SCHEMA_ACCEPTANCE_V1".equals(plan.get("protocol").asString())
                || !"gpt-6-luna".equals(plan.get("model").asString()) || plan.get("maxRequests").asInt() != MAX_REQUESTS
                || cap > plan.get("maxEstimatedUsd").asDouble())
            throw new IllegalStateException("Reviewed acceptance plan and unraised ceiling required");
        double prior = plan.get("priorReservationEstimateUsd").asDouble(); validateCaps(prior, cap);
        for (var input : plan.get("pinnedInputs")) {
            Path path = Path.of(input.get("path").asString()).normalize();
            if (path.isAbsolute() || path.startsWith("..") || !fileSha(path).equals(input.get("sha256").asString()))
                throw new IllegalStateException("Pinned public input or runner changed");
        }
        double computed = 0;
        for (var entry : priorPins().entrySet()) {
            Path path = Path.of(entry.getKey());
            if (!fileSha(path).equals(entry.getValue())) throw new IllegalStateException("Prior report changed");
            computed += JSON.readTree(Files.readAllBytes(path)).get("reservationEstimateUsd").asDouble();
        }
        if (Math.abs(prior - computed) > 1e-9) throw new IllegalStateException("Prior reservation mismatch");
        try (var reports = Files.list(OUT)) {
            if (reports.anyMatch(p -> p.getFileName().toString().matches("live-[0-9]+\\.json")))
                throw new IllegalStateException("Acceptance result already exists; review before another run");
        }
        return prior;
    }
    static boolean accepted(List<Map<String, Object>> rows) {
        return rows.size() == 2 && rows.stream().allMatch(row -> "ACCEPTED_SCHEMA".equals(row.get("status")));
    }

    @Test void fixedSchemasMatchTheCurrentProductAndKeepEveryPublicCandidateAndVerbatimQuote() throws Exception { probes(); }
    @Test void twoRequestsAndDollarCeilingsAreEnforcedBeforeTheProviderCall() throws Exception {
        var b = new QualityPilotBudget(MAX_USD);
        for (var probe : probes()) reserve(b, params("gpt-6-luna", probe).toString().length());
        assertEquals(2, b.requests); assertEquals(256, b.reservedOutput);
        assertThrows(QualityPilotBudget.LimitReached.class, () -> reserve(b, 100));
        assertThrows(QualityPilotBudget.LimitReached.class, () -> reserve(new QualityPilotBudget(MAX_USD), MAX_REQUEST_CHARS + 1));
        assertTrue(QualityPilotBudget.reservedEstimate(2, MAX_TOTAL_CHARS, 256) < MAX_USD);
        validateCaps(.3704967, .05);
        for (double cap : new double[]{0, -.1, .051, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalStateException.class, () -> validateCaps(.3704967, cap));
        assertThrows(IllegalStateException.class, () -> validateCaps(.99, .05));
    }
    @Test void bothSchemasMustBeAcceptedAndPartialRunsDoNotPass() {
        assertTrue(accepted(List.of(Map.of("status", "ACCEPTED_SCHEMA"), Map.of("status", "ACCEPTED_SCHEMA"))));
        for (String status : List.of("NOT_RUN", "REJECTED_INVALID_SCHEMA", "SDK_ERROR", "BUDGET_STOP"))
            assertFalse(accepted(List.of(Map.of("status", "ACCEPTED_SCHEMA"), Map.of("status", status))));
        assertFalse(accepted(List.of()));
    }
    @Test @EnabledIfSystemProperty(named = "quoteSchemaPrepared", matches = "true")
    void preparedPlanChecksBomHashesAndCumulativeReservationsAndRejectsAnIncreasedCeiling() throws Exception {
        Path path = OUT.resolve("run-plan.json");
        double cap = JSON.readTree(Files.readAllBytes(path)).get("maxEstimatedUsd").asDouble();
        assertEquals(.3704967, validatePlan(path, cap), 1e-9);
        assertThrows(IllegalStateException.class, () -> validatePlan(path, cap + .001));
    }
    @Test @EnabledIfSystemProperty(named = "quoteSchemaLive", matches = "true")
    void sendOnlyTwoCorrectedSchemasWithTheDedicatedPrivateEvaluationKey() throws Exception {
        var rows = new ArrayList<Map<String, Object>>(); var ps = probes();
        for (var probe : ps) {
            var row = new LinkedHashMap<String, Object>(); row.put("id", probe.id()); row.put("status", "NOT_RUN");
            row.put("schemaFileSha256", probe.fileSha256()); rows.add(row);
        }
        var report = new LinkedHashMap<String, Object>(); report.put("results", rows);
        report.put("evaluation", "PRODUCT_D09_D10_SCHEMA_ACCEPTANCE_NOT_RECOMMENDATION_QUALITY");
        report.put("productChanges", 0); report.put("databaseCalls", 0); report.put("state", "SETUP_REQUIRED");
        var budget = new QualityPilotBudget(MAX_USD); long start = System.nanoTime();
        try {
            double cap = Double.parseDouble(required("CHAT_EVAL_MAX_USD"));
            report.put("priorReservationEstimateUsd", validatePlan(OUT.resolve("run-plan.json"), cap));
            budget = new QualityPilotBudget(cap); final var reservation = budget;
            report.put("runPlanSha256", fileSha(OUT.resolve("run-plan.json")));
            String model = required("CHAT_EVAL_MODEL");
            if (!model.equals("gpt-6-luna")) throw new IllegalStateException("Explicitly approved model required");
            var p = new AiProperties(); p.setEnabled(true); p.setApiKey(required("CHAT_EVAL_OPENAI_API_KEY"));
            p.setModel(model); p.setTimeoutSeconds(20); p.setMaxOutputTokens(OUTPUT); p.validate();
            report.put("model", model); report.put("maxRequests", MAX_REQUESTS); report.put("maxEstimatedUsd", cap);
            report.put("maxOutputTokensPerRequest", OUTPUT); report.put("timeoutSecondsPerRequest", 20);
            var sdk = AiConfig.createSdk(p, "https://api.openai.com/v1");
            try {
                for (int i = 0; i < ps.size(); i++) {
                    if (System.nanoTime() - start >= Duration.ofMinutes(1).toNanos()) break;
                    var row = rows.get(i); var request = params(model, ps.get(i)); long tick = System.nanoTime();
                    try {
                        reserve(budget, request.toString().length());
                        var response = sdk.responses().create(request, RequestOptions.builder().timeout(Duration.ofSeconds(20)).build());
                        row.put("status", "ACCEPTED_SCHEMA");
                        row.put("responseState", response.status().map(Object::toString).orElse("UNKNOWN"));
                        response.usage().ifPresent(u -> { reservation.responsesWithUsage++; reservation.inputTokens += u.inputTokens();
                            reservation.outputTokens += u.outputTokens(); row.put("inputTokens", u.inputTokens()); row.put("outputTokens", u.outputTokens()); });
                    } catch (BadRequestException error) {
                        String code = error.code().orElse(""), param = error.param().orElse("");
                        row.put("status", code.equals("invalid_json_schema") && param.equals("text.format.schema")
                                ? "REJECTED_INVALID_SCHEMA" : "UNEXPECTED_BAD_REQUEST"); row.put("httpStatus", 400);
                        if ("REJECTED_INVALID_SCHEMA".equals(row.get("status"))) {
                            row.put("code", code); row.put("parameter", param);
                            try {
                                var body = error.body().convert(com.openai.models.ErrorObject.class);
                                safeValidationMessage(code, param, body == null ? null : body.message(), p.getApiKey())
                                        .ifPresent(message -> row.put("boundedSchemaValidationMessage", message));
                            } catch (RuntimeException ignored) { row.put("validationMessage", "WITHHELD"); }
                        }
                    } catch (OpenAIServiceException error) {
                        row.put("status", "PROVIDER_HTTP_ERROR"); row.put("httpStatus", error.statusCode());
                    } catch (OpenAIException error) { row.put("status", "SDK_ERROR");
                    } catch (QualityPilotBudget.LimitReached error) { row.put("status", "BUDGET_STOP"); }
                    row.put("latencyMs", (System.nanoTime() - tick) / 1_000_000);
                    if (!"ACCEPTED_SCHEMA".equals(row.get("status"))) break;
                }
            }
            finally { sdk.close(); }
            report.put("state", accepted(rows) ? "ACCEPTED_BOTH_SCHEMAS" : "FAILED_OR_INCOMPLETE");
        } finally {
            report.put("allSchemasAccepted", accepted(rows));
            report.put("providerRequests", budget.requests); report.put("responsesWithUsage", budget.responsesWithUsage);
            report.put("inputTokens", budget.inputTokens); report.put("outputTokens", budget.outputTokens);
            report.put("reservedOutputTokens", budget.reservedOutput); report.put("requestCharacters", budget.chars);
            double reserved = QualityPilotBudget.reservedEstimate(budget.requests, budget.chars, budget.reservedOutput);
            report.put("reservationEstimateUsd", reserved); report.put("observedUncachedListPriceEstimateUsd", budget.observedListPriceEstimate());
            if (report.containsKey("priorReservationEstimateUsd"))
                report.put("cumulativeReservationEstimateUsd", (double) report.get("priorReservationEstimateUsd") + reserved);
            report.put("billingTotalKnown", false); report.put("maxRetries", 0); report.put("finishedAtUtc", Instant.now().toString());
            Files.createDirectories(OUT);
            Files.writeString(OUT.resolve("live-" + Instant.now().toString().replaceAll("[^0-9]", "") + ".json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        assertTrue(accepted(rows), "Acceptance failed or incomplete; preserve report and never retry automatically");
    }
}
