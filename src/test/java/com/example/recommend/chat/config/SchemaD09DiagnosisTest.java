package com.example.recommend.chat.config;

import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.errors.*;
import com.openai.models.responses.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.recommend.chat.config.SchemaD10DiagnosisTest.copy;
import static com.example.recommend.chat.config.SchemaD10DiagnosisTest.read;
import static com.example.recommend.chat.config.SchemaD10DiagnosisTest.minimal;
import static com.example.recommend.chat.config.SchemaD10DiagnosisTest.sha;
import static com.example.recommend.chat.config.SchemaD10DiagnosisTest.required;
import static com.example.recommend.chat.config.SchemaD10DiagnosisTest.confirmed;
import static com.example.recommend.chat.config.SchemaD10DiagnosisTest.safeValidationMessage;

/** Fixed public-schema quote-character diagnosis only; never a product fix or recommendation gate. */
class SchemaD09DiagnosisTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path FIXTURE = Path.of("src/test/resources/chat/schema-d09-original-v1.json");
    static final Path OUT = Path.of("target/schema-d09-diagnosis-20261125");
    static final String PIN = "46ec07b294421bbc47089c4f60e4abc3e9d83086eb841b27142f6e673d8b5d09";
    static final int MAX_REQUESTS = 4, MAX_REQUEST_CHARS = 50_000, MAX_TOTAL_CHARS = 160_000, OUTPUT = 128;
    static final double MAX_USD = .10;
    record Probe(String id, Map<String, Object> schema) { }

    static String fileSha(Path path) throws Exception { return sha(Files.readAllBytes(path)); }

    @SuppressWarnings("unchecked") static List<Object> quoteEnum(Map<String, Object> schema) {
        var properties = (Map<String, Object>) schema.get("properties");
        var recommendations = (Map<String, Object>) properties.get("recommendations");
        var items = (Map<String, Object>) recommendations.get("items");
        var entry = (Map<String, Object>) ((List<Object>) items.get("anyOf")).get(5);
        var fields = (Map<String, Object>) entry.get("properties");
        assertEquals(List.of(434), ((Map<String, Object>) fields.get("contentId")).get("enum"));
        var evidence = (Map<String, Object>) fields.get("evidence");
        var evidenceItems = (Map<String, Object>) evidence.get("items");
        var variant = (Map<String, Object>) ((List<Object>) evidenceItems.get("anyOf")).get(0);
        var variantFields = (Map<String, Object>) variant.get("properties");
        assertEquals(List.of("OVERVIEW"), ((Map<String, Object>) variantFields.get("field")).get("enum"));
        return (List<Object>) ((Map<String, Object>) variantFields.get("quote")).get("enum");
    }
    static List<Probe> probes() throws Exception {
        byte[] bytes = Files.readAllBytes(FIXTURE); assertEquals(PIN, sha(bytes));
        var original = read(bytes); String text = (String) quoteEnum(original).get(0);
        assertEquals(1, text.chars().filter(ch -> ch == '"').count());
        assertFalse(text.codePoints().anyMatch(Character::isISOControl));
        var control = copy(original); quoteEnum(control).set(0, text.replace('"', '\''));
        return List.of(new Probe("FULL_ORIGINAL_DOUBLE_QUOTE", original), new Probe("FULL_SINGLE_QUOTE_CONTROL", control),
                new Probe("MINIMAL_DOUBLE_QUOTE", minimal(text)), new Probe("MINIMAL_SINGLE_QUOTE_CONTROL", minimal(text.replace('"', '\''))));
    }
    static void reserve(QualityPilotBudget budget, int chars) {
        if (budget.requests >= MAX_REQUESTS || chars > MAX_REQUEST_CHARS || budget.chars + chars > MAX_TOTAL_CHARS)
            throw new QualityPilotBudget.LimitReached();
        budget.reserve(chars, OUTPUT);
    }
    static void validateCaps(double prior, double cap) {
        if (!Double.isFinite(prior) || prior < 0 || !Double.isFinite(cap) || cap <= 0 || cap > MAX_USD || prior + cap > 1)
            throw new IllegalStateException("Fixed diagnosis or cumulative estimate exceeded");
    }
    static double validatePlan(double cap) throws Exception {
        var plan = JSON.readTree(Files.readAllBytes(OUT.resolve("run-plan.json")));
        if (!"D09_FIXED_QUOTE_CHARACTER_PROBE_V1".equals(plan.get("protocol").asString()))
            throw new IllegalStateException("Reviewed fixed diagnosis required");
        if (cap > plan.get("maxEstimatedUsd").asDouble())
            throw new IllegalStateException("Prepared lower ceiling must not be raised");
        double prior = plan.get("priorReservationEstimateUsd").asDouble(); validateCaps(prior, cap);
        for (var input : plan.get("pinnedInputs")) {
            Path path = Path.of(input.get("path").asString()).normalize();
            if (path.isAbsolute() || path.startsWith("..") || !fileSha(path).equals(input.get("sha256").asString()))
                throw new IllegalStateException("Pinned public input or runner changed");
        }
        double computed = 0;
        for (var input : plan.get("priorReports"))
            computed += JSON.readTree(Files.readString(Path.of(input.asString()))).get("reservationEstimateUsd").asDouble();
        if (Math.abs(prior - computed) > 1e-9) throw new IllegalStateException("Prior reservation mismatch");
        try (var reports = Files.list(OUT)) {
            if (reports.anyMatch(p -> p.getFileName().toString().matches("live-[0-9]+\\.json")))
                throw new IllegalStateException("Diagnosis result already exists; review before another run");
        }
        return prior;
    }

    @Test void originalSchemaIsPinnedAndItsPublicQuoteContainsOneDoubleQuoteWithoutControls() throws Exception {
        var ps = probes(); String text = (String) quoteEnum(ps.get(0).schema()).get(0);
        assertEquals(40, text.length()); assertTrue(text.endsWith("\""));
        assertEquals(text, quoteEnum(read(JSON.writeValueAsBytes(ps.get(0).schema()))).get(0));
    }
    @Test void fullControlChangesExactlyOneCharacterAndNothingElse() throws Exception {
        var ps = probes(); var restored = copy(ps.get(1).schema());
        String original = (String) quoteEnum(ps.get(0).schema()).get(0), changed = (String) quoteEnum(restored).get(0);
        assertEquals(original.replace('"', '\''), changed); assertEquals(original.length(), changed.length());
        assertEquals(1, java.util.stream.IntStream.range(0, original.length()).filter(i -> original.charAt(i) != changed.charAt(i)).count());
        quoteEnum(restored).set(0, original); assertEquals(ps.get(0).schema(), restored);
    }
    @Test void minimalPairIsTheSameSchemaApartFromTheSameSingleCharacter() throws Exception {
        var ps = probes(); String text = (String) quoteEnum(ps.get(0).schema()).get(0);
        assertEquals(minimal(text), ps.get(2).schema()); assertEquals(minimal(text.replace('"', '\'')), ps.get(3).schema());
    }
    @Test void requestAndCumulativeGuardsReserveFailuresAndKeepFixedBounds() {
        var budget = new QualityPilotBudget(MAX_USD);
        for (int i = 0; i < 4; i++) reserve(budget, 100);
        assertThrows(QualityPilotBudget.LimitReached.class, () -> reserve(budget, 100));
        assertEquals(4, budget.requests); assertEquals(512, budget.reservedOutput);
        assertThrows(QualityPilotBudget.LimitReached.class, () -> reserve(new QualityPilotBudget(MAX_USD), MAX_REQUEST_CHARS + 1));
        assertTrue(QualityPilotBudget.reservedEstimate(4, MAX_TOTAL_CHARS, 512) < MAX_USD);
        validateCaps(.3571191, .10);
        for (double cap : new double[]{0, -.1, .11, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalStateException.class, () -> validateCaps(.3571191, cap));
        assertThrows(IllegalStateException.class, () -> validateCaps(.95, .10));
    }
    @Test void onlyBoundedSchemaMessagesAreRetainedAndOtherPatternsDoNotConfirmTheHypothesis() {
        assertTrue(safeValidationMessage("invalid_json_schema", "text.format.schema", "Invalid schema: test", "synthetic-secret").isPresent());
        assertTrue(safeValidationMessage("invalid_json_schema", "text.format.schema", "Invalid schema synthetic-secret", "synthetic-secret").isEmpty());
        assertTrue(safeValidationMessage("invalid_api_key", "text.format.schema", "Invalid schema: test", "synthetic-secret").isEmpty());
        assertFalse(confirmed(List.of(Map.of("status", "ACCEPTED_SCHEMA"))));
    }

    @Test @EnabledIfSystemProperty(named = "schemaD09Live", matches = "true")
    void compareOnlyFourPinnedSchemasWithDedicatedEvaluationKey() throws Exception {
        var rows = new ArrayList<Map<String, Object>>(); var ps = probes();
        for (var probe : ps) {
            var row = new LinkedHashMap<String, Object>(); row.put("id", probe.id()); row.put("status", "NOT_RUN");
            row.put("schemaCanonicalSha256", sha(JSON.writeValueAsBytes(probe.schema()))); rows.add(row);
        }
        var report = new LinkedHashMap<String, Object>(); report.put("results", rows);
        report.put("evaluation", "D09_FIXED_SCHEMA_QUOTE_CHARACTER_PROBE_NOT_RECOMMENDATION_QUALITY");
        report.put("originalSchemaSha256", PIN); report.put("productChanges", 0); report.put("databaseCalls", 0);
        report.put("state", "SETUP_REQUIRED");
        var budget = new QualityPilotBudget(MAX_USD); long start = System.nanoTime();
        try {
            double cap = Double.parseDouble(required("CHAT_EVAL_MAX_USD"));
            report.put("priorReservationEstimateUsd", validatePlan(cap)); budget = new QualityPilotBudget(cap);
            report.put("runPlanSha256", fileSha(OUT.resolve("run-plan.json"))); final var reservation = budget;
            var p = new AiProperties(); p.setEnabled(true); p.setApiKey(required("CHAT_EVAL_OPENAI_API_KEY"));
            String model = required("CHAT_EVAL_MODEL");
            if (!model.equals("gpt-6-luna")) throw new IllegalStateException("Fixed explicitly approved model required");
            p.setModel(model); p.setTimeoutSeconds(20); p.setMaxOutputTokens(OUTPUT); p.validate();
            report.put("model", model); report.put("maxRequests", MAX_REQUESTS); report.put("maxEstimatedUsd", cap);
            report.put("maxOutputTokensPerRequest", OUTPUT); report.put("timeoutSecondsPerRequest", 20);
            var sdk = AiConfig.createSdk(p, "https://api.openai.com/v1");
            try {
                boolean stop = false;
                for (int i = 0; i < 4; i++) {
                    if (stop || System.nanoTime() - start >= Duration.ofMinutes(2).toNanos()) continue;
                    var row = rows.get(i); var schema = ResponseFormatTextJsonSchemaConfig.Schema.builder();
                    ps.get(i).schema().forEach((k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)));
                    var format = ResponseTextConfig.builder().format(ResponseFormatTextJsonSchemaConfig.builder()
                            .name("catalog_recommendations").strict(true).schema(schema.build()).build()).build();
                    var params = ResponseCreateParams.builder().model(model).store(false).maxOutputTokens(OUTPUT)
                            .input("Return one JSON object permitted by the supplied schema. No commentary.").text(format).build();
                    long tick = System.nanoTime();
                    try {
                        reserve(budget, params.toString().length());
                        var response = sdk.responses().create(params, RequestOptions.builder().timeout(Duration.ofSeconds(20)).build());
                        row.put("status", "ACCEPTED_SCHEMA");
                        row.put("responseState", response.status().map(Object::toString).orElse("UNKNOWN"));
                        response.usage().ifPresent(u -> { reservation.responsesWithUsage++; reservation.inputTokens += u.inputTokens();
                            reservation.outputTokens += u.outputTokens(); row.put("inputTokens", u.inputTokens()); row.put("outputTokens", u.outputTokens()); });
                    } catch (BadRequestException error) {
                        String code = error.code().orElse(""), param = error.param().orElse("");
                        if (code.equals("invalid_json_schema") && param.equals("text.format.schema")) {
                            row.put("status", "REJECTED_INVALID_SCHEMA"); row.put("httpStatus", 400);
                            row.put("code", code); row.put("parameter", param);
                            try {
                                var body = error.body().convert(com.openai.models.ErrorObject.class);
                                safeValidationMessage(code, param, body == null ? null : body.message(), p.getApiKey())
                                        .ifPresent(message -> row.put("boundedSchemaValidationMessage", message));
                            } catch (RuntimeException ignored) { row.put("validationMessage", "WITHHELD"); }
                        } else { row.put("status", "UNEXPECTED_BAD_REQUEST"); stop = true; }
                    } catch (OpenAIServiceException error) {
                        row.put("status", "PROVIDER_HTTP_ERROR"); row.put("httpStatus", error.statusCode()); stop = true;
                    } catch (OpenAIException error) { row.put("status", "SDK_ERROR"); stop = true;
                    } catch (QualityPilotBudget.LimitReached error) { row.put("status", "BUDGET_STOP"); stop = true; }
                    row.put("latencyMs", (System.nanoTime() - tick) / 1_000_000);
                }
            } finally { sdk.close(); }
            report.put("state", rows.stream().allMatch(r -> List.of("ACCEPTED_SCHEMA", "REJECTED_INVALID_SCHEMA").contains(r.get("status")))
                    ? "EXECUTED" : "INCOMPLETE");
        } finally {
            report.put("causeConfirmedForFixedSchema", confirmed(rows));
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
        assertEquals("EXECUTED", report.get("state"), "Fixed diagnosis incomplete; preserve report and do not retry silently");
    }
}
