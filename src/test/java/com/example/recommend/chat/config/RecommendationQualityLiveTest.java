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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Paid opt-in: isolated H2/public synthetic records only; at most 11 provider calls, no retries. */
@EnabledIfSystemProperty(named = "chatRecommendationLive", matches = "true")
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import(CatalogRetriever.class)
class RecommendationQualityLiveTest {
    @Autowired ContentRepository contents;
    @Autowired CatalogSnapshotRepository snapshots;
    @Autowired CatalogRetriever retriever;

    private long save(String title, String type, String genres, String overview, double rating) {
        return contents.saveAndFlush(Content.builder().title(title).type(type).genres(genres).overview(overview).rating(rating).build()).getId();
    }

    @Test void actualModelExplainsInitialRecommendationsAndHonorsWinterRetrieval() throws Exception {
        var properties = new AiProperties(); properties.setEnabled(true);
        properties.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY"));
        properties.setModel(System.getenv("CHAT_EVAL_MODEL")); properties.validate();
        var thrillerIds = Set.of(save("실종의 단서", "MOVIE", "drama,mystery,thriller", "배우자의 실종 사건을 조사하며 드러나는 서로 다른 증언.", 8),
                save("섬의 기록", "MOVIE", "mystery,thriller", "고립된 섬의 병원에서 사라진 사람을 찾는 수사관.", 7),
                save("형사의 선택", "MOVIE", "crime,thriller", "두 형사가 연쇄 사건의 단서를 추적한다.", 6));
        var winterIds = Set.of(save("하얀 마을", "MOVIE", "drama,family", "겨울 마을에서 이웃과 가족이 서로를 돕는 이야기.", 3),
                save("성탄절의 편지", "MOVIE", "drama,romance", "크리스마스를 앞두고 편지를 통해 서로를 알아가는 두 사람.", 2),
                save("눈 풍경의 기록", "TV", "documentary", "눈 덮인 산과 숲을 기록하는 겨울 여행 시리즈.", 1));
        save("그림 수업", "TV", "documentary,family", "편안한 목소리로 그림을 소개하며 보면 마음이 편해진다.", 10);
        save("가족 놀이", "TV", "animation,family", "일상을 활기찬 모험으로 바꾸는 가족의 놀이를 소개한다.", 10);
        record Example(String id, String question, Set<Long> allowed) { }
        var examples = List.of(new Example("initial-thriller-reasons", "스릴러 추천 해줘", thrillerIds),
                new Example("reported-winter-introduction", "겨울에 보면 좋을 작품 소개 해줘", winterIds),
                new Example("winter-no-supported-genre", "겨울 스릴러 영화 추천해줘", Set.of()));
        var calls = new AtomicInteger(); var rows = new ArrayList<Map<String, Object>>(); var started = Instant.now();
        try (var delegate = new AiConfig().aiClient(properties)) {
            AiClient client = new AiClient() {
                @Override public ChatPlan classify(List<AiMessage> messages, TurnBudget budget) {
                    calls.incrementAndGet(); var plan = delegate.classify(messages, budget);
                    assertEquals(1, plan.requests().size()); assertEquals(ChatIntent.Route.RECOMMEND, plan.requests().get(0).route());
                    return plan;
                }
                @Override public CatalogSelection recommendWithTools(List<AiMessage> messages, CatalogTools tools, TurnBudget budget) {
                    calls.incrementAndGet();
                    return delegate.recommendWithTools(messages, new CatalogTools() {
                        @Override public List<CatalogCandidate> candidates() { return tools.candidates(); }
                        @Override public boolean privateDataRead() { return tools.privateDataRead(); }
                        @Override public String execute(String name, String args) { calls.incrementAndGet(); return tools.execute(name, args); }
                    }, budget);
                }
                @Override public ChatResponse reply(List<AiMessage> messages) { throw new AssertionError("Unexpected generation branch"); }
            };
            var interactions = mock(ChatInteractionReader.class);
            var recommendations = new CatalogRecommendations(retriever, client, snapshots,
                    new CatalogToolService(retriever, interactions), interactions);
            var router = new ChatRouter(client, recommendations, interactions, mock(CatalogDetails.class), properties);
            for (var example : examples) {
                var row = new LinkedHashMap<String, Object>(); row.put("id", example.id()); int before = calls.get();
                try {
                    var context = List.of(new AiMessage(AiMessage.Role.USER, "안녕"), new AiMessage(AiMessage.Role.ASSISTANT, "안녕하세요!"),
                            new AiMessage(AiMessage.Role.USER, example.question()));
                    var response = router.reply(7, context);
                    if (example.allowed().isEmpty()) {
                        assertFalse(response.catalogGrounded()); assertTrue(response.contentIds().isEmpty());
                        assertTrue(response.message().contains("찾지 못했어요")); assertEquals(1, calls.get() - before);
                    } else {
                        assertTrue(response.catalogGrounded()); assertFalse(response.contentIds().isEmpty());
                        assertTrue(example.allowed().containsAll(response.contentIds()));
                        assertEquals(response.contentIds().size(), response.message().split("추천 이유\\(모델의 해석\\):", -1).length - 1);
                        assertTrue(response.message().contains("카탈로그 근거:"));
                    }
                    verifyNoInteractions(interactions);
                    row.put("status", "PASS"); row.put("contentIds", response.contentIds());
                    row.put("inputTokens", response.usage().inputTokens()); row.put("outputTokens", response.usage().outputTokens());
                } catch (ChatException e) { row.put("status", e.code()); }
                catch (AssertionError e) { row.put("status", "EXPECTATION_MISMATCH"); }
                row.put("providerCalls", calls.get() - before); rows.add(row);
                if (Set.of("AI_BUSY", "AI_TIMEOUT", "AI_UNAVAILABLE").contains(row.get("status"))) break;
                Thread.sleep(1000);
            }
        } finally {
            Files.createDirectories(Path.of("target/chat-quality"));
            var report = Map.of("evaluation", "initial-recommendation-quotes-and-winter-constraints", "model", properties.getModel(),
                    "startedAt", started.toString(), "providerCalls", calls.get(), "expectedCases", examples.size(), "results", rows);
            Files.writeString(Path.of("target/chat-quality/recommendations-" + started.toString().replaceAll("[^0-9]", "") + ".json"),
                    ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        assertEquals(examples.size(), rows.size());
        assertTrue(rows.stream().allMatch(r -> r.get("status").equals("PASS")), "Inspect sanitized target/chat-quality/recommendations report");
    }
}
