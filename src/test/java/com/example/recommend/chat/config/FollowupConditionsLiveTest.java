package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.openai.core.RequestOptions;
import com.openai.models.responses.ResponseCreateParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Six predeclared synthetic cases, three repetitions. Separate from immutable v1 scores. */
@EnabledIfSystemProperty(named = "chatFollowupLive", matches = "true")
class FollowupConditionsLiveTest {
    static ClassifierStabilityCorpus.Expected expected(String kind, String value) {
        return new ClassifierStabilityCorpus.Expected(kind, value);
    }
    static ClassifierStabilityCorpus.Case test(String id, String prior, String current,
            ClassifierStabilityCorpus.Expected... constraints) {
        var context = new ArrayList<AiMessage>();
        if (prior != null) {
            context.add(new AiMessage(AiMessage.Role.USER, prior));
            context.add(new AiMessage(AiMessage.Role.ASSISTANT, "합성 공개 작품 두 편을 추천해요.", List.of(42L, 73L)));
        }
        context.add(new AiMessage(AiMessage.Role.USER, current));
        return new ClassifierStabilityCorpus.Case(id, List.copyOf(context), List.of("RECOMMEND"), List.of(constraints));
    }
    static List<ClassifierStabilityCorpus.Case> cases() throws Exception {
        String mood = "우울해서 기분 전환할 영화 추천해줘. 한 시간 반 이하로, 공포는 빼고";
        return List.of(
                ClassifierStabilityCorpus.load().stream().filter(c -> c.id().equals("audit-shorter")).findFirst().orElseThrow(),
                test("fresh-mood", null, mood, expected("MEDIA", "MOVIE"), expected("MOOD", "*"),
                        expected("MINUTES_MAX", "90"), expected("GENRE_EXCLUDE", "horror")),
                test("commute-tv-shorter", "출퇴근길에 볼 한 회 20분 이하 애니메이션 시리즈 추천해줘", "그중 더 짧은 걸로 골라줘",
                        expected("MEDIA", "TV"), expected("SITUATION", "*"), expected("EPISODE_MINUTES_MAX", "20"),
                        expected("GENRE_ALL", "animation"), expected("REFERENCE", "ONLY_LAST"), expected("ORDER", "SHORTER_EPISODE")),
                test("mood-shorter-no-numeric-limit", "기분 전환할 영화 추천해줘", "그중 더 짧은 걸로 골라줘",
                        expected("MEDIA", "MOVIE"), expected("MOOD", "*"), expected("REFERENCE", "ONLY_LAST"), expected("ORDER", "SHORTER_MOVIE")),
                test("replace-mood-with-situation", mood, "기분 전환 조건은 빼고 출퇴근길에 볼 용도로 추천해줘. 나머지 조건은 유지해줘",
                        expected("MEDIA", "MOVIE"), expected("SITUATION", "*"), expected("MINUTES_MAX", "90"), expected("GENRE_EXCLUDE", "horror")),
                test("reset-all", mood, "이전 조건은 모두 잊고 액션 영화 추천해줘", expected("MEDIA", "MOVIE"), expected("GENRE_ALL", "action")));
    }

    @Test void recordsBoundedSyntheticFollowupPlansAndIncompleteResponses() throws Exception {
        String scope = System.getProperty("chatFollowupScope", "all");
        if (!Set.of("all", "diagnostic").contains(scope)) throw new IllegalArgumentException("Unknown fixed followup scope");
        var cases = cases().stream().limit(scope.equals("diagnostic") ? 3 : 6).toList();
        var p = new AiProperties(); p.setEnabled(true); p.setMaxOutputTokens(2048);
        p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY")); p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        var traces = new ArrayList<Map<String, Object>>(); var results = new ArrayList<Map<String, Object>>(); var started = Instant.now();
        var sdk = AiConfig.createSdk(p, "https://api.openai.com/v1");
        var intercepted = mock(com.openai.client.OpenAIClient.class); var resource = spy(sdk.responses());
        when(intercepted.responses()).thenReturn(resource);
        doAnswer(call -> {
            var item = new LinkedHashMap<String, Object>(); traces.add(item);
            var response = sdk.responses().create((ResponseCreateParams) call.getArgument(0), (RequestOptions) call.getArgument(1));
            item.put("status", response.status().map(Object::toString).orElse("missing"));
            response.incompleteDetails().ifPresent(d -> item.put("incompleteReason", d.reason().map(Object::toString).orElse("missing")));
            response.usage().ifPresent(u -> { item.put("inputTokens", u.inputTokens()); item.put("outputTokens", u.outputTokens()); });
            var text = new StringBuilder();
            response.output().forEach(i -> i.message().ifPresent(m -> m.content().forEach(c -> c.outputText().ifPresent(t -> text.append(t.text())))));
            item.put("outputChars", text.length());
            if (!text.isEmpty() && text.length() <= 16000) item.put("syntheticOutput", text.toString());
            return response;
        }).when(resource).create(any(ResponseCreateParams.class), any(RequestOptions.class));
        var delegate = new OpenAiClient(intercepted, p);
        // Keep trace association correct even if a classification fails before reaching the SDK.
        var client = mock(AiClient.class);
        var attempts = new ArrayList<List<Map<String, Object>>>();
        doAnswer(call -> {
            int before = traces.size();
            try { return delegate.classify(call.getArgument(0), call.getArgument(1)); }
            finally { attempts.add(List.copyOf(traces.subList(before, traces.size()))); }
        }).when(client).classify(anyList(), any());
        try {
            ClassifierStabilityLiveTest.run(client, p, cases, results, () -> Thread.sleep(1000));
        } finally {
            sdk.close(); int attempt = 0;
            for (var row : results) row.put("providerTrace", row.get("status").equals("NOT_RUN") ? List.of() : attempts.get(attempt++));
            Files.createDirectories(Path.of("target/chat-quality"));
            Files.writeString(Path.of("target/chat-quality/followup-" + started.toString().replaceAll("[^0-9]", "") + ".json"),
                    ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "evaluation", "fixed-synthetic-followup-conditions", "scope", scope, "model", p.getModel(),
                            "totalOutputTokens", 2048, "classificationMaxOutputTokens", new TurnBudget(p).classificationTokens(),
                            "repeats", 3, "cases", cases, "results", results)));
        }
        assertEquals(cases.size() * 3, results.size());
        assertTrue(results.stream().allMatch(r -> r.get("status").equals("PASS")), "Followup quality gate failed; preserve initial report");
    }
}
