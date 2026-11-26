package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.config.AiProperties;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.domain.*;
import com.example.recommend.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQL/tools/storage with scripted classification, never an actual-model quality score. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import({CatalogRetriever.class, CatalogRecommendations.class, CatalogToolService.class, ChatInteractionReader.class,
        ChatRouter.class, CatalogDetails.class, ChatHistoryStore.class, ConversationService.class,
        ChatRequestLimiter.class, TopicRecommendationTest.Dependencies.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TopicRecommendationTest {
    @TestConfiguration static class Dependencies {
        @Bean AiClient client() { return mock(AiClient.class); }
        @Bean AiProperties properties() { var p = new AiProperties(); p.setEnabled(true); p.setRequestsPerMinute(60); return p; }
    }
    @Autowired AiClient client;
    @Autowired ContentRepository contents;
    @Autowired UserRepository users;
    @Autowired ChatHistoryStore history;
    @Autowired ConversationService service;
    @Autowired CatalogRetriever retriever;
    long owner, room, chef, longChef, tv, incidental, space;
    List<Long> ids = new ArrayList<>();
    String question = "흑백요리사 같이 조리나 요리 관련된 영화 추천해줘";

    @BeforeEach void setup() {
        reset(client);
        owner = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.invalid").nickname("합성 주제 검사").password("hash").build()).getId();
        room = history.create(owner).id();
        chef = save("합성 셰프의 여정", "MOVIE", "셰프가 주방에서 조리하며 새 요리를 개발한다.", 85);
        longChef = save("합성 조리 대회", "MOVIE", "요리 대회를 준비하는 주방 팀의 이야기.", 120);
        tv = save("합성 요리 방송", "TV", "셰프가 매회 요리하는 방송.", null);
        incidental = save("합성 수사 기록", "MOVIE", "음식점에서 증거를 찾은 형사가 범죄 조직을 추적한다.", 80);
        space = save("합성 우주 탐험", "MOVIE", "우주선으로 행성을 탐험한다.", 80);
        when(client.classify(anyList(), any())).thenAnswer(call -> plan(question, false, false));
        when(client.recommendWithTools(anyList(), any(), any())).thenAnswer(call -> {
            CatalogTools tools = call.getArgument(1);
            var selected = tools.candidates().stream().limit(2).toList();
            var reasons = new LinkedHashMap<Long, CatalogRationale>();
            selected.forEach(w -> reasons.put(w.id(), new CatalogRationale(List.of(new CatalogRationale.Evidence(
                    CatalogRationale.Field.OVERVIEW, w.overview())), "제목·줄거리에 명시된 소재가 요청과 연결돼요.")));
            return new CatalogSelection(selected.stream().map(CatalogCandidate::id).toList(), new ChatResponse.Usage(0, 0, 0), reasons);
        });
    }
    long save(String title, String type, String overview, Integer minutes) {
        long id = contents.saveAndFlush(Content.builder().title(title).type(type).genres("drama")
                .overview(overview).runtimeMinutes(minutes).build()).getId(); ids.add(id); return id;
    }
    static RecommendationConstraint topic(String source, String... terms) {
        return new RecommendationConstraint(RecommendationConstraint.Kind.valueOf("TOPIC"), "cooking", source, List.of(terms));
    }
    static ChatPlan plan(String source, boolean narrow, boolean reset) {
        var conditions = new ArrayList<RecommendationConstraint>();
        conditions.add(new RecommendationConstraint(RecommendationConstraint.Kind.MEDIA, "MOVIE", "영화", List.of()));
        conditions.add(topic(reset ? "우주 탐험" : source.contains("조리나 요리") ? "조리나 요리" : "요리",
                reset ? new String[]{"우주", "행성"} : new String[]{"요리", "조리", "셰프", "주방"}));
        if (narrow) {
            conditions.add(new RecommendationConstraint(RecommendationConstraint.Kind.MINUTES_MAX, "90", "90분 이하", List.of()));
            conditions.add(new RecommendationConstraint(RecommendationConstraint.Kind.REFERENCE, "ONLY_LAST", "그중", List.of()));
        }
        return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY)), false, conditions);
    }
    List<Long> storedIds() { var context = history.prepare(owner, room, "다음 질문").context(); return context.get(context.size() - 2).contentIds(); }
    @AfterEach void cleanup() { history.delete(owner, room); users.deleteById(owner); contents.deleteAllById(ids); }

    @Test void explicitTopicWithNamedExampleUsesPlotCuesWithoutNeedingTheExampleInCatalog() {
        var result = service.reply(owner, room, question);
        assertTrue(result.catalogGrounded()); assertEquals(Set.of(chef, longChef), new HashSet<>(storedIds()));
        assertTrue(result.messages().get(1).text().contains("주방"));
        assertFalse(result.messages().get(1).text().contains("흑백요리사 (ID:"));
        assertEquals(2, history.history(owner, room, null).messages().size());
    }
    @Test void followupKeepsTopicAndTrustedScopeThenResetReplacesIt() {
        service.reply(owner, room, question);
        when(client.classify(anyList(), any())).thenReturn(plan(question, true, false));
        var narrowed = service.reply(owner, room, "그중 90분 이하 영화로 추천해줘");
        assertEquals(List.of(chef), storedIds());
        when(client.classify(anyList(), any())).thenReturn(plan("우주 탐험 영화", false, true));
        var reset = service.reply(owner, room, "이전 조건 모두 잊고 우주 탐험 영화 추천해줘");
        assertEquals(List.of(space), storedIds());
    }
    @Test void genreOnlyEvidenceIsRejectedWithoutPersistingAnyPartOfTurn() {
        doReturn(new CatalogSelection(List.of(chef),
                new ChatResponse.Usage(0,0,0), Map.of(chef,new CatalogRationale(List.of(
                new CatalogRationale.Evidence(CatalogRationale.Field.GENRES,"drama")), "요리 주제예요."))))
                .when(client).recommendWithTools(anyList(),any(),any());
        assertThrows(ChatException.class, () -> service.reply(owner, room, question));
        assertTrue(history.history(owner, room, null).messages().isEmpty());
    }
    @Test void unrelatedButVerbatimEvidenceIsRejected() {
        var work = contents.findById(chef).orElseThrow(); work.setOverview("어린 시절 친구를 만나 재회한다. 셰프가 주방에서 요리한다."); contents.saveAndFlush(work);
        doReturn(new CatalogSelection(List.of(chef),new ChatResponse.Usage(0,0,0),
                Map.of(chef,new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.OVERVIEW,"어린 시절 친구를 만나 재회한다.")),"요리 주제예요."))))
                .when(client).recommendWithTools(anyList(),any(),any());
        assertThrows(ChatException.class, () -> service.reply(owner,room,question));
        assertTrue(history.history(owner,room,null).messages().isEmpty());
    }
    @Test void emptyTopicExplainsCatalogLimitWithoutCallingRecommendationGeneration() {
        when(client.classify(anyList(), any())).thenReturn(new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RECOMMEND,ChatIntent.Media.ANY)),false,
                List.of(topic("제빵","제빵","베이킹"))));
        var result = service.reply(owner,room,"제빵 주제 영화 추천해줘");
        assertFalse(result.catalogGrounded()); assertTrue(storedIds().isEmpty());
        assertTrue(result.messages().get(1).text().contains("주제")); assertTrue(result.messages().get(1).text().contains("제빵"));
        verify(client,never()).recommendWithTools(anyList(),any(),any());
    }
    @Test void toolsCannotReplaceTopicOrMediaWithAnUnrelatedQueryOrId() {
        doAnswer(call -> {
            CatalogTools tools=call.getArgument(1);
            assertEquals(Set.of(chef,longChef),new HashSet<>(tools.candidates().stream().map(CatalogCandidate::id).toList()));
            assertFalse(tools.execute("get_content","{\"contentId\":"+incidental+"}").contains("합성 수사"));
            assertFalse(tools.execute("get_content","{\"contentId\":"+tv+"}").contains("합성 요리 방송"));
            assertFalse(tools.execute("search_catalog","{\"query\":\"우주\"}").contains("합성 우주"));
            return new CatalogSelection(List.of(),new ChatResponse.Usage(0,0,0),Map.of());
        }).when(client).recommendWithTools(anyList(),any(),any());
        assertFalse(service.reply(owner,room,question).catalogGrounded());
    }
    @Test void themeAfterVisibleOverviewLimitIsNotInferredFromHiddenText() {
        long hidden=save("합성 긴 기록","MOVIE","가".repeat(405)+"셰프가 요리한다.",80);
        var criteria=RecommendationCriteria.from(plan(question,false,false).constraints(),List.of(new AiMessage(AiMessage.Role.USER,question)),question);
        assertFalse(retriever.retrieve(List.of(new AiMessage(AiMessage.Role.USER,question)),criteria).stream().anyMatch(w->w.id()==hidden));
    }
    @Test void topicPatternsAreLiteralAndIndependentTopicsIntersect() {
        long literal=save("합성 100% 조리","MOVIE","우주 정거장에서 셰프가 요리한다.",80);
        save("합성 100점 조리","MOVIE","우주 정거장에서 셰프가 요리한다.",80);
        String request="100% 조리와 우주 주제 영화";
        var criteria=RecommendationCriteria.from(List.of(topic("100% 조리","100%"),topic("우주","우주")),
                List.of(new AiMessage(AiMessage.Role.USER,request)),request);
        assertEquals(List.of(literal),retriever.retrieve(List.of(new AiMessage(AiMessage.Role.USER,request)),criteria)
                .stream().map(CatalogCandidate::id).toList());
        assertThrows(ChatException.class,()->topic("요리"));
    }
    @Test void cookingDoesNotMatchInsideTheUnrelatedWordMojori() {
        long noisy=save("합성 선물 도둑","MOVIE","선물과 음식을 모조리 빼앗으려는 도둑 이야기.",80);
        String request="조리 주제 영화";
        var criteria=RecommendationCriteria.from(List.of(topic("조리","조리")),List.of(new AiMessage(AiMessage.Role.USER,request)),request);
        assertFalse(criteria.matches(CatalogCandidate.from(contents.findById(noisy).orElseThrow())));
        assertFalse(retriever.retrieve(List.of(new AiMessage(AiMessage.Role.USER,request)),criteria).stream().anyMatch(w->w.id()==noisy));
    }
}
