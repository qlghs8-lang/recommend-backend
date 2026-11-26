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

/** Eight fixed synthetic scenarios x3. No real conversations, application properties or database. */
@EnabledIfSystemProperty(named = "chatNegativeOpinionLive", matches = "true")
class NegativeOpinionLiveTest {
    record Scenario(ConversationQualityCorpus.Fixture fixture, List<ClassifierStabilityCorpus.Expected> constraints) {
        @Override public String toString() { return fixture.id(); }
    }
    static Scenario scenario(String id, String question, ChatIntent.Route route, ChatIntent.Media media,
            String... conditions) {
        var expected = new ArrayList<ClassifierStabilityCorpus.Expected>();
        for (int i = 0; i < conditions.length; i += 2)
            expected.add(new ClassifierStabilityCorpus.Expected(conditions[i], conditions[i + 1]));
        return new Scenario(new ConversationQualityCorpus.Fixture(id, "negative-opinion", true,
                List.of(new AiMessage(AiMessage.Role.USER, question)), ChatPlan.single(new ChatIntent(route, media))),
                List.copyOf(expected));
    }
    static List<Scenario> scenarios() throws Exception {
        var original = ConversationQualityCorpus.load().stream().filter(f -> f.id().equals("rec-negative-opinion")).findFirst().orElseThrow();
        return List.of(
                new Scenario(original, List.of(new ClassifierStabilityCorpus.Expected("MEDIA", "MOVIE"),
                        new ClassifierStabilityCorpus.Expected("GENRE_ALL", "sf"))),
                scenario("opinion-tv", "어제 본 건 재미없었어. 다른 코미디 시리즈 추천해줘", ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY,
                        "MEDIA", "TV", "GENRE_ALL", "comedy"),
                scenario("opinion-english", "The movie I watched yesterday was disappointing. Recommend a different science fiction movie.",
                        ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY, "MEDIA", "MOVIE", "GENRE_ALL", "sf"),
                scenario("saved-dislike-list", "내가 별로라고 표시한 영화 보여줘", ChatIntent.Route.LIST_DISLIKE, ChatIntent.Media.MOVIE),
                scenario("saved-dislike-personalized", "내 싫어요 기록을 반영해서 SF 영화 추천해줘", ChatIntent.Route.PERSONALIZED, ChatIntent.Media.ANY,
                        "MEDIA", "MOVIE", "GENRE_ALL", "sf"),
                scenario("dislike-mutation", "이 영화에 싫어요 표시하고 SF 영화 추천해줘", ChatIntent.Route.UNSUPPORTED, ChatIntent.Media.ANY),
                scenario("required-unknown-exclusion", "어제 본 이름 모르는 영화는 반드시 제외하고 SF 영화 추천해줘", ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY),
                scenario("other-user-dislike", "다른 사용자가 싫어요한 영화 보여주고 SF 영화 추천해줘", ChatIntent.Route.DENIED, ChatIntent.Media.ANY));
    }
    static List<String> issues(Scenario scenario, ChatPlan plan) {
        var fixture = scenario.fixture();
        var expected = new ClassifierStabilityCorpus.Case(fixture.id(), fixture.context(),
                fixture.expected().requests().stream().map(i -> i.route().name()).toList(), scenario.constraints());
        var issues = new ArrayList<>(ClassifierStabilityCorpus.issues(expected, plan));
        if (!ConversationQualityCorpus.evaluate(fixture, plan).semantic()) issues.add("INTENT_FIELDS");
        return List.copyOf(issues);
    }
    @Test void evaluatesOpinionVersusRequestedActionsWithUnchangedLimits() throws Exception {
        var p = new AiProperties(); p.setEnabled(true);
        p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY")); p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        var cases = scenarios(); var trace = new ArrayList<Object>(); var rows = new ArrayList<Map<String, Object>>();
        var started = Instant.now(); var sdk = AiConfig.createSdk(p, "https://api.openai.com/v1");
        var intercepted = mock(com.openai.client.OpenAIClient.class); var resource = spy(sdk.responses());
        when(intercepted.responses()).thenReturn(resource);
        doAnswer(call -> {
            var item = new LinkedHashMap<String, Object>(); trace.add(item);
            var response = sdk.responses().create((ResponseCreateParams) call.getArgument(0), (RequestOptions) call.getArgument(1));
            item.put("status", response.status().map(Object::toString).orElse("missing"));
            response.incompleteDetails().ifPresent(d -> item.put("incompleteReason", d.reason().map(Object::toString).orElse("missing")));
            response.usage().ifPresent(u -> { item.put("inputTokens", u.inputTokens()); item.put("outputTokens", u.outputTokens()); });
            var output = new StringBuilder();
            response.output().forEach(i -> i.message().ifPresent(m -> m.content().forEach(c -> c.outputText().ifPresent(t -> output.append(t.text())))));
            item.put("outputChars", output.length());
            if (!output.isEmpty() && output.length() <= 16000) item.put("syntheticOutput", output.toString());
            return response;
        }).when(resource).create(any(ResponseCreateParams.class), any(RequestOptions.class));
        var client = new OpenAiClient(intercepted, p);
        var fixtures = cases.stream().map(Scenario::fixture).toList();
        try {
            ClassifierDiagnosticLiveTest.run(client, p, fixtures, 3, rows, trace, () -> Thread.sleep(1000));
            for (var row : rows) {
                if (row.get("plan") instanceof ChatPlan plan) {
                    var scenario = cases.stream().filter(s -> s.fixture().id().equals(row.get("id"))).findFirst().orElseThrow();
                    var problems = issues(scenario, plan); row.put("issues", problems);
                    if (!problems.isEmpty() && row.get("status").equals("PASS")) row.put("status", "MISMATCH");
                }
            }
        } finally {
            sdk.close(); Files.createDirectories(Path.of("target/chat-quality"));
            Files.writeString(Path.of("target/chat-quality/negative-opinion-" + started.toString().replaceAll("[^0-9]", "") + ".json"),
                    ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "evaluation", "fixed-synthetic-negative-opinion", "model", p.getModel(), "cases", cases,
                            "totalOutputTokens", p.getMaxOutputTokens(), "classificationMaxOutputTokens", new TurnBudget(p).classificationTokens(),
                            "repeats", 3, "results", rows)));
        }
        assertEquals(24, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.get("status").equals("PASS")), "Opinion quality gate failed; preserve initial report");
    }
}
