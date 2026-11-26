package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Paid opt-in; eight fixed synthetic scope cases, exactly three repetitions. */
@EnabledIfSystemProperty(named = "chatCompoundMediaLive", matches = "true")
class CompoundMediaLiveTest {
    record Scenario(ClassifierStabilityCorpus.Case test, String splitAfter, ChatIntent.Media listMedia) { }
    static Scenario scenario(String id, String question, boolean reversed, String media, String split,
            ChatIntent.Media listMedia, boolean newer, String listRoute) {
        var expected = new ArrayList<ClassifierStabilityCorpus.Expected>();
        if (!media.equals("ANY")) expected.add(new ClassifierStabilityCorpus.Expected("MEDIA", media));
        if (newer) expected.add(new ClassifierStabilityCorpus.Expected("ORDER", "NEWER"));
        return new Scenario(new ClassifierStabilityCorpus.Case(id, List.of(new AiMessage(AiMessage.Role.USER, question)),
                reversed ? List.of("PERSONALIZED", listRoute) : List.of(listRoute, "PERSONALIZED"), expected), split, listMedia);
    }
    static List<Scenario> scenarios() {
        return List.of(
                scenario("movie-period", "내 찜 목록을 보여줘. 그리고 내 취향으로 새 영화 추천해줘", false, "MOVIE", "보여줘.", ChatIntent.Media.ANY, false, "LIST_BOOKMARK"),
                scenario("tv-no-period", "내 찜 목록을 보여줘 그리고 내 취향으로 새 시리즈 추천해줘", false, "TV", "보여줘", ChatIntent.Media.ANY, false, "LIST_BOOKMARK"),
                scenario("movie-list-tv-rec", "내 영화 좋아요 목록 보여주고 내 취향으로 시리즈 추천해줘", false, "TV", "보여주고", ChatIntent.Media.MOVIE, false, "LIST_LIKE"),
                scenario("reversed-tv-list", "내 취향으로 새 영화 추천해줘. 다음으로 내 시리즈 찜 목록 보여줘", true, "MOVIE", "추천해줘.", ChatIntent.Media.TV, false, "LIST_BOOKMARK"),
                scenario("unknown-rec-media", "내 영화 찜 목록 보여주고 내 취향으로 작품 추천해줘", false, "ANY", "보여주고", ChatIntent.Media.MOVIE, false, "LIST_BOOKMARK"),
                scenario("explicit-latest-movie", "내 찜 목록 보여주고 내 취향으로 최신 공개순 영화 추천해줘", false, "MOVIE", "보여주고", ChatIntent.Media.ANY, true, "LIST_BOOKMARK"),
                scenario("unseen-movie", "내 찜 목록 보여주고 내 취향으로 아직 반응하지 않은 영화 추천해줘", false, "MOVIE", "보여주고", ChatIntent.Media.ANY, false, "LIST_BOOKMARK"),
                scenario("explicit-latest-tv", "내 취향으로 최신 공개순 시리즈 추천해줘. 그리고 내 찜 목록 보여줘", true, "TV", "추천해줘.", ChatIntent.Media.ANY, true, "LIST_BOOKMARK"));
    }
    static boolean listScopeMatches(Scenario scenario, ChatPlan plan) {
        return plan.requests().stream().filter(r -> r.route().name().startsWith("LIST_"))
                .allMatch(r -> r.media() == scenario.listMedia());
    }
    @Test void repeatsFixedMediaScopeCasesWithoutChangingTheOriginalOracles() throws Exception {
        var p = new AiProperties(); p.setEnabled(true); p.setMaxOutputTokens(2048);
        p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY")); p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        var scenarios = scenarios(); var results = new ArrayList<Map<String, Object>>(); var started = Instant.now();
        try (var client = new AiConfig().aiClient(p)) {
            ClassifierStabilityLiveTest.run(client, p, scenarios.stream().map(Scenario::test).toList(), results, () -> Thread.sleep(1000));
            for (var row : results) if (row.get("actualPlan") instanceof ChatPlan plan) {
                var scenario = scenarios.stream().filter(s -> s.test().id().equals(row.get("id"))).findFirst().orElseThrow();
                if (!listScopeMatches(scenario, plan)) {
                    var issues = new ArrayList<>((List<String>) row.get("issues")); issues.add("LIST_MEDIA_SCOPE");
                    row.put("issues", issues); row.put("status", "MISMATCH");
                }
            }
        } finally {
            Files.createDirectories(Path.of("target/chat-quality"));
            Files.writeString(Path.of("target/chat-quality/compound-media-" + started.toString().replaceAll("[^0-9]", "") + ".json"),
                    ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "evaluation", "frozen-compound-media-scope", "model", p.getModel(), "totalOutputTokens", 2048,
                            "classificationMaxOutputTokens", new TurnBudget(p).classificationTokens(), "timeoutSeconds", 20,
                            "repeats", 3, "plannedAttempts", 24, "cases", scenarios, "results", results)));
        }
        assertEquals(24, results.size());
        assertTrue(results.stream().allMatch(r -> r.get("status").equals("PASS")), "Compound media quality gate failed; inspect synthetic report");
    }
}
