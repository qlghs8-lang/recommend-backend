package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.ChatException;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.core.RequestOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded synthetic diagnostic replays; NEVER replace the initial baseline score. */
@EnabledIfSystemProperty(named = "chatClassifierDiagnostics", matches = "true")
class ClassifierDiagnosticLiveTest {
    @Test void recordsSyntheticPlansForBoundedFailureTriage() throws Exception {
        var p = new AiProperties(); p.setEnabled(true);
        p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY")); p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        String scope = System.getProperty("chatClassifierDiagnosticScope", "baseline");
        var ids = selectedIds(scope);
        int repeats = scope.equals("baseline") ? 1 : 3;
        var results = new ArrayList<Map<String, Object>>(); var trace = new ArrayList<Object>(); var started = Instant.now();
        var sdk = AiConfig.createSdk(p, "https://api.openai.com/v1");
        var intercepted = mock(com.openai.client.OpenAIClient.class); var resource = spy(sdk.responses());
        when(intercepted.responses()).thenReturn(resource);
        doAnswer(call -> {
            var response = sdk.responses().create((ResponseCreateParams) call.getArgument(0), (RequestOptions) call.getArgument(1));
            var item = new LinkedHashMap<String, Object>();
            item.put("status", response.status().map(Object::toString).orElse("missing"));
            response.incompleteDetails().ifPresent(d -> item.put("incompleteReason", d.reason().map(Object::toString).orElse("missing")));
            response.usage().ifPresent(u -> { item.put("inputTokens", u.inputTokens()); item.put("outputTokens", u.outputTokens()); });
            var text = new StringBuilder();
            response.output().forEach(i -> i.message().ifPresent(m -> m.content().forEach(c -> c.outputText().ifPresent(t -> text.append(t.text())))));
            item.put("outputChars", text.length());
            if (text.length() <= 16000 && !text.isEmpty()) item.put("syntheticOutput", text.toString());
            trace.add(item); return response;
        }).when(resource).create(any(ResponseCreateParams.class), any(RequestOptions.class));
        var client = new OpenAiClient(intercepted, p);
        try {
            var fixtures = ConversationQualityCorpus.load().stream().filter(f -> ids.contains(f.id())).toList();
            run(client, p, fixtures, repeats, results, trace, () -> Thread.sleep(1000));
        } finally {
            sdk.close();
            Files.createDirectories(Path.of("target/chat-quality"));
            Files.writeString(Path.of("target/chat-quality/classifier-diagnostic-" + started.toString().replaceAll("[^0-9]", "") + ".json"),
                    ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "evaluation", "diagnostic-replay-only-not-baseline-score", "scope", scope, "repeats", repeats,
                            "model", p.getModel(), "totalOutputTokens", 800,
                            "classificationMaxOutputTokens", new TurnBudget(p).classificationTokens(), "results", results)));
        }
        if (!scope.equals("baseline")) {
            assertEquals(ids.size() * repeats, results.size());
            assertTrue(results.stream().allMatch(r -> r.get("status").equals("PASS")),
                    "Synthetic diagnostic gate failed; original baseline score remains unchanged");
        }
    }

    static Set<String> selectedIds(String scope) {
        return switch (scope) {
            case "baseline" -> Set.of("detail-title", "ref-single", "compare-ids", "compound-detail-rec");
            case "comparison" -> Set.of("compare-ids", "compare-titles", "compare-polite");
            // Keep the original representative order through the corpus filter, including the eight preceding calls.
            case "representative" -> Set.of("rec-thriller", "personal-taste", "list-dislike-movie", "detail-title",
                    "explain-noir", "ref-single", "clarify-many", "compound-list-rec", "compare-ids",
                    "privacy-other", "unsupported-delete", "scope-weather");
            default -> throw new IllegalArgumentException("Unknown diagnostic scope");
        };
    }

    static void run(AiClient client, AiProperties p, List<ConversationQualityCorpus.Fixture> fixtures,
            int repeats, List<Map<String, Object>> results, List<Object> trace,
            ConversationQualityLiveTest.Pause pause) throws InterruptedException {
        boolean stopped = false;
        for (int repetition = 1; repetition <= repeats; repetition++) for (var fixture : fixtures) {
            var row = new LinkedHashMap<String, Object>(); results.add(row);
            row.put("id", fixture.id()); row.put("repetition", repetition); row.put("status", "NOT_RUN");
            int before = trace.size(); var budget = new TurnBudget(p); long start = System.nanoTime();
            if (!stopped) try {
                var plan = client.classify(fixture.context(), budget); row.put("plan", plan);
                var verdict = ConversationQualityCorpus.evaluate(fixture, plan);
                row.put("semanticMatch", verdict.semantic()); row.put("routerAccepted", verdict.executable());
                row.put("status", verdict.passed() ? "PASS" : "MISMATCH");
            } catch (ChatException e) {
                row.put("status", e.code()); stopped = e.status() == 429 || e.status() == 503 || e.status() == 504;
            }
            row.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
            row.put("providerTrace", List.copyOf(trace.subList(before, trace.size())));
            if (!stopped) pause.await();
        }
    }
}
