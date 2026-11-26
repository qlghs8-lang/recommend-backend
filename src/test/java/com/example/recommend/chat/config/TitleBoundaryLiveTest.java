package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in, fixed synthetic title-boundary policy, not a replacement baseline. */
@EnabledIfSystemProperty(named = "chatTitleBoundaryLive", matches = "true")
class TitleBoundaryLiveTest {
    @Test @EnabledIfSystemProperty(named = "chatTitleBoundarySchemaProbe", matches = "true")
    void probesOnlyTheFixedDoubleQuotedSyntheticSchema() throws Exception {
        var p = new AiProperties(); p.setEnabled(true);
        p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY")); p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        var scenario = scenarios().stream().filter(s -> s.fixture().id().equals("quoted-full-double")).findFirst().orElseThrow();
        var evidence = new LinkedHashMap<String, Object>(); evidence.put("caseId", scenario.fixture().id());
        var sdk = AiConfig.createSdk(p, "https://api.openai.com/v1");
        try {
            var intercepted = mock(com.openai.client.OpenAIClient.class); var resource = spy(sdk.responses());
            when(intercepted.responses()).thenReturn(resource);
            doAnswer(call -> {
                try {
                    return sdk.responses().create((com.openai.models.responses.ResponseCreateParams) call.getArgument(0),
                            (com.openai.core.RequestOptions) call.getArgument(1));
                } catch (com.openai.errors.BadRequestException error) {
                    if (error.code().orElse("").equals("invalid_json_schema") && error.param().orElse("").equals("text.format.schema")) {
                        var body = error.body().convert(com.openai.models.ErrorObject.class);
                        String message = body == null ? "" : body.message();
                        // This single fixed synthetic probe alone may retain the schema validator's message.
                        // Never serialize exceptions, headers, causes, whole bodies or arbitrary error messages.
                        if (message.startsWith("Invalid schema") && message.length() <= 2000 && !message.contains(p.getApiKey())
                                && !message.toLowerCase(Locale.ROOT).contains("authorization") && !message.contains("api_key"))
                            evidence.put("syntheticSchemaValidationMessage", message);
                    }
                    throw error;
                }
            }).when(resource).create(any(com.openai.models.responses.ResponseCreateParams.class), any(com.openai.core.RequestOptions.class));
            try {
                var budget = new TurnBudget(p);
                var plan = new OpenAiClient(intercepted, p).classify(scenario.fixture().context(), budget);
                evidence.put("status", "ACCEPTED"); evidence.put("issues", issues(scenario, plan));
                evidence.put("usage", budget.usage());
            } catch (com.example.recommend.chat.service.ChatException error) { evidence.put("status", error.code()); }
        } finally {
            sdk.close();
            Files.createDirectories(Path.of("target/title-boundary"));
            Files.writeString(Path.of("target/title-boundary/schema-probe.json"), ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }
    }
    record Scenario(ConversationQualityCorpus.Fixture fixture, String reason) { }
    static List<Scenario> scenarios() throws Exception {
        try (var stream = TitleBoundaryLiveTest.class.getResourceAsStream("/chat/title-boundary-v1.json")) {
            assertNotNull(stream);
            var root = ConversationQualityCorpus.JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            var cases = new ArrayList<Scenario>();
            for (var row : root.get("cases")) {
                var context = new ArrayList<AiMessage>();
                for (var message : row.get("history")) {
                    var ids = new ArrayList<Long>(); for (var id : message.get("contentIds")) ids.add(id.asLong());
                    context.add(new AiMessage(AiMessage.Role.valueOf(message.get("role").asString()), message.get("text").asString(), ids));
                }
                context.add(new AiMessage(AiMessage.Role.USER, row.get("question").asString()));
                cases.add(new Scenario(new ConversationQualityCorpus.Fixture(row.get("id").asString(), "title-boundary", true,
                        List.copyOf(context), ChatPlan.parse(row.get("expected").toString())),
                        row.get("reason").isNull() ? null : row.get("reason").asString()));
            }
            return List.copyOf(cases);
        }
    }
    static List<String> issues(Scenario scenario, ChatPlan plan) {
        var issues = new ArrayList<String>();
        if (!ConversationQualityCorpus.evaluate(scenario.fixture(), plan).passed()) issues.add("TITLE_LOCATOR_OR_ROUTER");
        if (scenario.reason() == null ? plan.clarification() != null
                : plan.clarification() == null || !plan.clarification().reason().name().equals(scenario.reason())) issues.add("CLARIFICATION_REASON");
        if (!plan.constraints().isEmpty()) issues.add("UNEXPECTED_CONSTRAINTS");
        return List.copyOf(issues);
    }

    @Test void evaluatesFixedTitleBoundaryCasesWithoutChangingOriginalOracles() throws Exception {
        var p = new AiProperties(); p.setEnabled(true);
        p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY")); p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        var cases = scenarios(); var results = new ArrayList<Map<String, Object>>(); var started = Instant.now();
        String previous = System.getProperty("chatQualityRecordSyntheticPlans"); System.setProperty("chatQualityRecordSyntheticPlans", "true");
        try (var trace = new SyntheticClassifierTrace(p, AiConfig.createSdk(p, "https://api.openai.com/v1"))) {
            try {
                ConversationQualityLiveTest.run(trace.client, p, cases.stream().map(Scenario::fixture).toList(), 3,
                        results, () -> Thread.sleep(1000), trace.items);
                for (var row : results) if (row.get("actualPlan") instanceof ChatPlan plan) {
                    var scenario = cases.stream().filter(s -> s.fixture().id().equals(row.get("id"))).findFirst().orElseThrow();
                    var problems = issues(scenario, plan); row.put("issues", problems);
                    if (!problems.isEmpty() && row.get("status").equals("PASS")) row.put("status", "MISMATCH");
                }
            } finally {
                Files.createDirectories(Path.of("target/chat-quality"));
                Files.writeString(Path.of("target/chat-quality/title-boundary-" + started.toString().replaceAll("[^0-9]", "") + ".json"),
                        ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                                "evaluation", "separate-synthetic-title-boundary-policy-not-v1-baseline", "model", p.getModel(),
                                "totalOutputTokens", p.getMaxOutputTokens(), "classificationMaxOutputTokens", new TurnBudget(p).classificationTokens(),
                                "repeats", 3, "cases", cases, "results", results)));
            }
        } finally {
            if (previous == null) System.clearProperty("chatQualityRecordSyntheticPlans");
            else System.setProperty("chatQualityRecordSyntheticPlans", previous);
        }
        assertEquals(cases.size() * 3, results.size());
        assertTrue(results.stream().allMatch(row -> row.get("status").equals("PASS")), "Separate title-boundary policy failed; original score is preserved");
    }
}
