package com.example.recommend.chat.config;

import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.errors.*;
import com.openai.models.responses.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed four-request causal probe. No application/DB access or product modifications. */
class SchemaD10DiagnosisTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path FIXTURE = Path.of("src/test/resources/chat/schema-d10-original-v1.json");
    static final Path OUT = Path.of("target/schema-d10-diagnosis-20261125");
    static final String PIN = "a17426126d3df3266172cf49539b0edd95e9fc87c9cd2f35a6742dbd41665f25";
    static final int MAX_REQUESTS = 4, MAX_REQUEST_CHARS = 50_000, MAX_TOTAL_CHARS = 160_000, OUTPUT = 128;
    static final double MAX_USD = .10;
    record Probe(String id, Map<String, Object> schema) { }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    @SuppressWarnings("unchecked") static Map<String, Object> read(byte[] bytes) throws Exception {
        return JSON.readValue(bytes, Map.class);
    }
    static Map<String, Object> copy(Map<String, Object> original) throws Exception {
        return read(JSON.writeValueAsBytes(original));
    }
    @SuppressWarnings("unchecked") static List<Object> quoteEnum(Map<String, Object> schema) {
        var properties = (Map<String, Object>) schema.get("properties");
        var recommendations = (Map<String, Object>) properties.get("recommendations");
        var items = (Map<String, Object>) recommendations.get("items");
        var entries = (List<Object>) items.get("anyOf");
        var entry = (Map<String, Object>) entries.get(10);
        var fields = (Map<String, Object>) entry.get("properties");
        var contentId = (Map<String, Object>) fields.get("contentId");
        assertEquals(List.of(404), contentId.get("enum"));
        var evidence = (Map<String, Object>) fields.get("evidence");
        var evidenceItems = (Map<String, Object>) evidence.get("items");
        var variants = (List<Object>) evidenceItems.get("anyOf");
        var variant = (Map<String, Object>) variants.get(0);
        var variantFields = (Map<String, Object>) variant.get("properties");
        var quote = (Map<String, Object>) variantFields.get("quote");
        return (List<Object>) quote.get("enum");
    }
    static Map<String, Object> minimal(String quote) {
        return Map.of("type", "object", "properties", Map.of("quote", Map.of("type", "string", "enum", List.of(quote))),
                "required", List.of("quote"), "additionalProperties", false);
    }
    static List<Probe> probes() throws Exception {
        byte[] bytes = Files.readAllBytes(FIXTURE); assertEquals(PIN, sha(bytes));
        var original = read(bytes); String text = (String) quoteEnum(original).get(0);
        assertEquals(2, text.chars().filter(c -> c == '\n').count());
        var control = copy(original); quoteEnum(control).set(0, text.replace('\n', ' '));
        return List.of(new Probe("FULL_ORIGINAL_LF", original), new Probe("FULL_LF_TO_SPACE_CONTROL", control),
                new Probe("MINIMAL_LF", minimal(text)), new Probe("MINIMAL_SPACE_CONTROL", minimal(text.replace('\n', ' '))));
    }
    static void reserve(QualityPilotBudget budget, int chars) {
        if (budget.requests >= MAX_REQUESTS || chars > MAX_REQUEST_CHARS || budget.chars + chars > MAX_TOTAL_CHARS)
            throw new QualityPilotBudget.LimitReached();
        budget.reserve(chars, OUTPUT);
    }
    static Optional<String> safeValidationMessage(String code, String parameter, String message, String secret) {
        if (!"invalid_json_schema".equals(code) || !"text.format.schema".equals(parameter)
                || message == null || !message.startsWith("Invalid schema") || message.length() > 2000
                || secret == null || secret.isBlank() || message.contains(secret)) return Optional.empty();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("authorization") || lower.contains("api_key") || lower.contains("bearer ")) return Optional.empty();
        return Optional.of(message);
    }
    static boolean confirmed(List<Map<String, Object>> rows) {
        return rows.size() == 4 && List.of("REJECTED_INVALID_SCHEMA", "ACCEPTED_SCHEMA", "REJECTED_INVALID_SCHEMA", "ACCEPTED_SCHEMA")
                .equals(rows.stream().map(r -> r.get("status")).toList());
    }
    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Dedicated evaluation environment is required");
        return value;
    }

    @Test void originalPublicSchemaIsPinnedAndOnlyControlCharactersAreTwoLineFeeds() throws Exception {
        var ps = probes(); assertEquals(4, ps.size());
        String serialized = JSON.writeValueAsString(ps.get(0).schema());
        assertEquals(2, serialized.split("\\\\n", -1).length - 1);
    }
    @Test void fullControlChangesOnlyThePinnedEnumValueAndPreservesEverythingElse() throws Exception {
        var ps = probes(); var restored = copy(ps.get(1).schema());
        String original = (String) quoteEnum(ps.get(0).schema()).get(0);
        assertEquals(original.replace('\n', ' '), quoteEnum(restored).get(0));
        quoteEnum(restored).set(0, original); assertEquals(ps.get(0).schema(), restored);
        assertEquals(original.length(), ((String) quoteEnum(ps.get(1).schema()).get(0)).length());
    }
    @Test void minimalControlIsTheSameSchemaApartFromTheSameTwoCharacters() throws Exception {
        var ps = probes(); String original = (String) quoteEnum(ps.get(0).schema()).get(0);
        assertEquals(minimal(original), ps.get(2).schema());
        assertEquals(minimal(original.replace('\n', ' ')), ps.get(3).schema());
    }
    @Test void fourRequestAndDollarGuardsReserveBeforeCallingAndNeverRefundFailures() {
        var budget = new QualityPilotBudget(MAX_USD);
        for (int i = 0; i < 4; i++) reserve(budget, 100);
        assertThrows(QualityPilotBudget.LimitReached.class, () -> reserve(budget, 100));
        assertEquals(4, budget.requests); assertEquals(512, budget.reservedOutput);
        assertThrows(QualityPilotBudget.LimitReached.class, () -> reserve(new QualityPilotBudget(MAX_USD), MAX_REQUEST_CHARS + 1));
        assertTrue(QualityPilotBudget.reservedEstimate(4, MAX_TOTAL_CHARS, 512) < MAX_USD);
    }
    @Test void arbitraryErrorMessagesAndSecretsAreNeverRetained() {
        assertTrue(safeValidationMessage("invalid_json_schema", "text.format.schema", "Invalid schema: test", "synthetic-secret").isPresent());
        for (String message : List.of("Invalid schema synthetic-secret", "Invalid schema Authorization bearer abc", "arbitrary body"))
            assertTrue(safeValidationMessage("invalid_json_schema", "text.format.schema", message, "synthetic-secret").isEmpty());
        assertTrue(safeValidationMessage("invalid_api_key", "text.format.schema", "Invalid schema: test", "synthetic-secret").isEmpty());
        assertFalse(confirmed(List.of(Map.of("status", "ACCEPTED_SCHEMA"))));
    }

    @Test @EnabledIfSystemProperty(named = "schemaD10Live", matches = "true")
    void compareOnlyFourFixedSchemasWithDedicatedPrivateEvaluationKey() throws Exception {
        var rows = new ArrayList<Map<String, Object>>(); var ps = probes();
        for (var probe : ps) {
            var row = new LinkedHashMap<String, Object>(); row.put("id", probe.id()); row.put("status", "NOT_RUN");
            row.put("schemaCanonicalSha256", sha(JSON.writeValueAsBytes(probe.schema()))); rows.add(row);
        }
        var report = new LinkedHashMap<String, Object>(); report.put("results", rows);
        report.put("evaluation", "D10_FIXED_SCHEMA_CAUSAL_PROBE_NOT_RECOMMENDATION_QUALITY");
        report.put("originalSchemaSha256", PIN); report.put("productChanges", 0); report.put("databaseCalls", 0);
        report.put("state", "SETUP_REQUIRED");
        var budget = new QualityPilotBudget(MAX_USD); long start = System.nanoTime();
        try {
            var p = new AiProperties(); p.setEnabled(true); p.setApiKey(required("CHAT_EVAL_OPENAI_API_KEY"));
            String model = required("CHAT_EVAL_MODEL");
            if (!model.equals("gpt-6-luna")) throw new IllegalStateException("Fixed explicitly approved model required");
            p.setModel(model); p.setTimeoutSeconds(20); p.setMaxOutputTokens(OUTPUT); p.validate();
            report.put("model", model); report.put("maxRequests", MAX_REQUESTS); report.put("maxEstimatedUsd", MAX_USD);
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
                        response.usage().ifPresent(u -> { budget.responsesWithUsage++; budget.inputTokens += u.inputTokens();
                            budget.outputTokens += u.outputTokens(); row.put("inputTokens", u.inputTokens()); row.put("outputTokens", u.outputTokens()); });
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
            report.put("reservationEstimateUsd", QualityPilotBudget.reservedEstimate(budget.requests, budget.chars, budget.reservedOutput));
            report.put("observedUncachedListPriceEstimateUsd", budget.observedListPriceEstimate());
            report.put("billingTotalKnown", false); report.put("maxRetries", 0); report.put("finishedAtUtc", Instant.now().toString());
            Files.createDirectories(OUT);
            Files.writeString(OUT.resolve("live-" + Instant.now().toString().replaceAll("[^0-9]", "") + ".json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        assertEquals("EXECUTED", report.get("state"), "Fixed schema diagnosis incomplete; preserve report and do not retry silently");
    }
}
