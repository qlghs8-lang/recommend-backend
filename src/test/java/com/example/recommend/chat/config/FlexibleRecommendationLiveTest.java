package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.*;
import com.example.recommend.domain.Content;
import com.example.recommend.repository.ContentRepository;
import com.example.recommend.chat.repository.CatalogSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import static com.example.recommend.chat.client.RecommendationConstraint.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Paid opt-in. Ten isolated H2/public synthetic examples, at most 50 provider requests, zero retries. */
@EnabledIfSystemProperty(named = "chatFlexibleLive", matches = "true")
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import(CatalogRetriever.class)
class FlexibleRecommendationLiveTest {
    @Autowired ContentRepository contents;
    @Autowired CatalogSnapshotRepository snapshots;
    @Autowired CatalogRetriever retriever;
    long save(String title, String type, String genres, int year, Integer movie, Integer episode, String overview, double rating) {
        return contents.saveAndFlush(Content.builder().title(title).type(type).genres(genres).releaseDate(LocalDate.of(year, 1, 1))
                .runtimeMinutes(movie).episodeRuntimeMinutes(episode).overview(overview).rating(rating).build()).getId();
    }
    static boolean has(ChatPlan plan, RecommendationConstraint.Kind kind, String value) {
        return plan.constraints().stream().anyMatch(c -> c.kind() == kind && (value == null || c.value().equals(value)));
    }
    static List<AiMessage> question(String text) { return List.of(new AiMessage(AiMessage.Role.USER, text)); }
    static List<AiMessage> followup(String before, List<Long> ids, String text) {
        return List.of(new AiMessage(AiMessage.Role.USER, before), new AiMessage(AiMessage.Role.ASSISTANT, "공개 카탈로그 추천 작품", ids),
                new AiMessage(AiMessage.Role.USER, text));
    }

    @Test void actualModelExtractsAndExecutesGeneralConstraintsAndFollowupChanges() throws Exception {
        var p = new AiProperties(); p.setEnabled(true); p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY"));
        p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        long shortFilm = save("친구의 도전", "MOVIE", "comedy,drama", 2015, 90, null, "유쾌한 친구들이 서로 도우며 희망을 찾는다.", 2);
        long longFilm = save("웃음 여행", "MOVIE", "comedy", 2020, 119, null, "친구와 함께 웃음과 희망을 찾아 여행한다.", 3);
        long boundary = save("성탄절 편지", "MOVIE", "romance", 2021, 120, null, "크리스마스의 겨울 마을에서 사랑을 찾는다.", 5);
        long horror = save("어두운 방", "MOVIE", "horror,thriller", 2022, 80, null, "고립된 방에서 잔혹한 사건을 맞는다.", 10);
        long episode = save("바다 친구들", "TV", "animation,comedy", 2020, null, 20, "여름 바다에서 유쾌한 친구들이 모험한다.", 4);
        long summer = save("여름의 해변", "MOVIE", "drama", 2018, 100, null, "여름 해변에서 친구들과 추억을 만드는 여행.", 3);
        save("길이 미등록 다큐", "MOVIE", "documentary", 2020, null, null, "자연을 관찰하는 기록.", 10);
        record Example(String id, List<AiMessage> context, Set<Long> allowed, Predicate<ChatPlan> check) { }
        var examples = List.of(
                new Example("combined-mood-runtime-exclusion", question("우울해서 기분 전환할 영화 추천해줘. 한 시간 반 이하로, 공포는 빼고"), Set.of(shortFilm),
                        plan -> has(plan, MINUTES_MAX, "90") && has(plan, GENRE_EXCLUDE, "horror") && (has(plan, MOOD, null) || has(plan, SITUATION, null))),
                new Example("strict-under-two-hours", question("2시간 미만인 로맨스 영화 추천해줘"), Set.of(),
                        plan -> has(plan, MINUTES_MAX, "119")),
                new Example("short-tv-episode", question("한 회가 20분 이하인 애니메이션 시리즈 추천해줘"), Set.of(episode),
                        plan -> has(plan, EPISODE_MINUTES_MAX, "20")),
                new Example("summer-setting", question("여름 바다가 배경인 영화 추천해줘"), Set.of(summer), plan -> has(plan, SETTING, null)),
                new Example("winter-general-setting", question("겨울에 보면 좋을 작품 소개 해줘"), Set.of(boundary), plan -> has(plan, SETTING, null)),
                new Example("unknown-runtime-is-not-a-match", question("90분 이하 다큐멘터리 영화 추천해줘"), Set.of(), plan -> has(plan, MINUTES_MAX, "90")),
                new Example("unsupported-absence-guarantee", question("잔인한 장면이 전혀 없는 영화 추천해줘"), Set.of(),
                        plan -> has(plan, AVOID, null) || has(plan, UNVERIFIABLE, null)),
                new Example("shorter-within-trusted-list", followup("두 시간 이내 영화 추천", List.of(shortFilm, longFilm), "그중 더 짧은 걸로 골라줘"), Set.of(shortFilm),
                        plan -> has(plan, REFERENCE, "ONLY_LAST") && has(plan, ORDER, "SHORTER_MOVIE")),
                new Example("replace-genre-retain-time", followup("두 시간 이내 로맨스 영화 추천", List.of(boundary), "로맨스는 빼고 다시 추천해줘"), Set.of(shortFilm, longFilm, horror, summer),
                        plan -> has(plan, MINUTES_MAX, "120") && has(plan, GENRE_EXCLUDE, "romance") && !has(plan, GENRE_ALL, "romance")),
                new Example("avoid-repeat-public-works", followup("코미디 영화 추천", List.of(shortFilm), "방금 추천한 작품 말고 다른 코미디 영화로 추천해줘"), Set.of(longFilm),
                        plan -> has(plan, REFERENCE, "EXCLUDE_RECENT")));
        String filter = System.getProperty("chatFlexibleCases", "all");
        var selected = examples.stream().filter(e -> filter.equals("all") || Set.of(filter.split(",")).contains(e.id())).toList();
        assertFalse(selected.isEmpty());
        var rows = new ArrayList<Map<String, Object>>(); var calls = new AtomicInteger(); var started = Instant.now();
        var diagnostic = new AtomicReference<Map<String, Object>>(Map.of());
        // Diagnostics exist ONLY in this opt-in synthetic harness, never in the production client or private chat logs.
        var sdk = AiConfig.createSdk(p, "https://api.openai.com/v1");
        try (AutoCloseable closeSdk = sdk::close) {
            var resource = spy(sdk.responses());
            var intercepted = mock(com.openai.client.OpenAIClient.class);
            when(intercepted.responses()).thenReturn(resource);
            doAnswer(call -> {
                var response = sdk.responses().create((com.openai.models.responses.ResponseCreateParams) call.getArgument(0),
                        (com.openai.core.RequestOptions) call.getArgument(1));
                var text = new StringBuilder(); response.output().forEach(item -> item.message().ifPresent(message -> message.content().forEach(part -> part.outputText().ifPresent(t -> text.append(t.text())))));
                var info = new LinkedHashMap<String, Object>(); info.put("status", response.status().map(Object::toString).orElse("missing"));
                info.put("outputTokens", response.usage().map(u -> u.outputTokens()).orElse(0L));
                try {
                    var root = ConversationQualityCorpus.JSON.readTree(text.toString());
                    if (root.has("requests")) {
                        info.put("routes", java.util.stream.StreamSupport.stream(root.get("requests").spliterator(), false)
                                .map(r -> Map.of("route", r.path("route").asString(), "media", r.path("media").asString())).toList());
                        var conditions = new ArrayList<Map<String, Object>>();
                        if (root.has("constraints")) for (var c : root.get("constraints")) {
                            var entry = new LinkedHashMap<String, Object>();
                            try {
                                var parsed = RecommendationConstraint.parse(c); entry.put("kind", parsed.kind().name()); entry.put("valid", true);
                                if (parsed.terms().isEmpty() && parsed.kind() != UNVERIFIABLE) entry.put("value", parsed.value());
                            } catch (RuntimeException ignored) { entry.put("valid", false); entry.put("fieldCount", c.size()); }
                            conditions.add(entry);
                        }
                        info.put("constraints", conditions); diagnostic.set(info);
                    }
                } catch (RuntimeException ignored) { info.put("json", "INVALID_OR_INCOMPLETE"); diagnostic.set(info); }
                return response;
            }).when(resource).create(any(com.openai.models.responses.ResponseCreateParams.class), any(com.openai.core.RequestOptions.class));
            var delegate = new OpenAiClient(intercepted, p);
            for (var example : selected) {
                diagnostic.set(Map.of());
                var row = new LinkedHashMap<String, Object>(); row.put("id", example.id()); int before = calls.get();
                AiClient client = new AiClient() {
                    @Override public ChatPlan classify(List<AiMessage> messages, TurnBudget budget) {
                        calls.incrementAndGet(); var plan = delegate.classify(messages, budget);
                        assertEquals(1, plan.requests().size()); assertEquals(ChatIntent.Route.RECOMMEND, plan.requests().get(0).route());
                        assertTrue(example.check().test(plan)); return plan;
                    }
                    @Override public CatalogSelection recommendWithTools(List<AiMessage> messages, CatalogTools tools, TurnBudget budget) {
                        calls.incrementAndGet();
                        row.put("initialCandidateIds", tools.candidates().stream().map(CatalogCandidate::id).toList());
                        row.put("referenceScope", tools.referenceScope());
                        var names = new ArrayList<String>(); row.put("tools", names);
                        var selectedWorks = delegate.recommendWithTools(messages, new CatalogTools() {
                            @Override public List<CatalogCandidate> candidates() { return tools.candidates(); }
                            @Override public List<RecommendationConstraint> constraints() { return tools.constraints(); }
                            @Override public Map<String, List<Long>> referenceScope() { return tools.referenceScope(); }
                            @Override public boolean privateDataRead() { return tools.privateDataRead(); }
                            @Override public String execute(String name, String args) { calls.incrementAndGet(); names.add(name); return tools.execute(name, args); }
                        }, budget);
                        row.put("selectedIds", selectedWorks.contentIds());
                        row.put("evidenceCounts", selectedWorks.reasons().entrySet().stream().collect(java.util.stream.Collectors.toMap(e -> Long.toString(e.getKey()), e -> e.getValue().evidence().size())));
                        return selectedWorks;
                    }
                    @Override public ChatResponse reply(List<AiMessage> messages) { throw new AssertionError("Unexpected generation branch"); }
                };
                var interactions = mock(ChatInteractionReader.class);
                var recommendations = new CatalogRecommendations(retriever, client, snapshots, new CatalogToolService(retriever, interactions), interactions);
                try {
                    var response = new ChatRouter(client, recommendations, interactions, mock(CatalogDetails.class), p).reply(7, example.context());
                    row.put("contentIds", response.contentIds()); row.put("inputTokens", response.usage().inputTokens()); row.put("outputTokens", response.usage().outputTokens());
                    if (example.allowed().isEmpty()) {
                        assertFalse(response.catalogGrounded()); assertTrue(response.contentIds().isEmpty()); assertEquals(1, calls.get() - before);
                    } else {
                        assertTrue(response.catalogGrounded()); assertFalse(response.contentIds().isEmpty());
                        assertTrue(example.allowed().containsAll(response.contentIds()));
                        assertTrue(response.message().contains("추천 이유(모델의 해석):"));
                    }
                    verifyNoInteractions(interactions);
                    row.put("status", "PASS"); row.put("contentIds", response.contentIds());
                    row.put("inputTokens", response.usage().inputTokens()); row.put("outputTokens", response.usage().outputTokens());
                } catch (ChatException e) { row.put("status", e.code()); }
                catch (AssertionError e) { row.put("status", "EXPECTATION_MISMATCH"); }
                row.put("providerCalls", calls.get() - before); row.put("classification", diagnostic.get()); rows.add(row);
                if (Set.of("AI_BUSY", "AI_TIMEOUT", "AI_UNAVAILABLE").contains(row.get("status"))) break;
                Thread.sleep(1000);
            }
        } finally {
            Files.createDirectories(Path.of("target/chat-quality"));
            Files.writeString(Path.of("target/chat-quality/flexible-" + started.toString().replaceAll("[^0-9]", "") + ".json"),
                    ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("evaluation", "flexible-criteria-and-grounding",
                            "model", p.getModel(), "startedAt", started.toString(), "providerCalls", calls.get(), "expectedCases", selected.size(), "corpusSize", examples.size(), "results", rows)));
        }
        assertEquals(selected.size(), rows.size());
        assertTrue(rows.stream().allMatch(r -> r.get("status").equals("PASS")), "Inspect sanitized target/chat-quality/flexible report");
    }
}
