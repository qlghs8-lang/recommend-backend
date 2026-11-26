package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.*;
import com.example.recommend.domain.User;
import com.example.recommend.repository.*;
import com.openai.core.RequestOptions;
import com.openai.models.responses.ResponseCreateParams;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.recommend.chat.config.QualityBaselineTest.*;
import static com.example.recommend.chat.config.QualityConversationTrace.*;

/** Frozen synthetic questions, public catalog and actual H2 history. No application DB access. */
@DataJpaTest(showSql=false, properties={"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "app.ai.enabled=true",
        "app.ai.api-key=scripted-no-network", "app.ai.model=scripted", "app.ai.max-output-tokens=2048",
        "app.ai.timeout-seconds=20", "app.ai.requests-per-minute=60", "app.ai.global-requests-per-minute=600"})
@Import({ChatHistoryStore.class, ConversationService.class, ChatRouter.class, CatalogDetails.class,
        CatalogRecommendations.class, CatalogRetriever.class, CatalogToolService.class, ChatInteractionReader.class,
        ChatRequestLimiter.class, QualityConversationTest.Dependencies.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class QualityConversationTest {
    static Path output() {
        return Boolean.getBoolean("qualityConversationV2") ? QualityConversationV2Plan.OUT : QualityConversationPlan.OUT;
    }
    static String protocol() {
        return Boolean.getBoolean("qualityConversationV2") ? QualityConversationV2Plan.PROTOCOL : QualityConversationPlan.PROTOCOL;
    }
    @TestConfiguration static class Dependencies {
        @Bean AiClient client() { return mock(AiClient.class); }
        @Bean AiProperties properties() {
            var p = new AiProperties(); p.setEnabled(true); p.setApiKey("scripted-no-network"); p.setModel("scripted");
            p.setMaxOutputTokens(2048); p.setTimeoutSeconds(20); p.setRequestsPerMinute(60); return p;
        }
    }
    @Autowired ConversationService service;
    @Autowired ChatHistoryStore history;
    @Autowired UserRepository users;
    @Autowired ContentRepository contents;
    @Autowired CatalogRetriever retriever;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired DataSource dataSource;
    @Autowired AiClient client;
    @Autowired AiProperties properties;
    final List<Long> rooms = new ArrayList<>();
    final List<Long> accountIds = new ArrayList<>();
    QualityBaselineTest fixture;
    double livePrior, liveCap;

    void install() throws Exception {
        try (var c = dataSource.getConnection()) { assertTrue(c.getMetaData().getURL().startsWith("jdbc:h2:mem:")); }
        fixture = new QualityBaselineTest(); fixture.dataSource=dataSource; fixture.contents=contents; fixture.em=em; fixture.retriever=retriever;
        fixture.install(JSON.readTree(Files.readAllBytes(OUT.resolve("public-snapshot.json"))));
        for (String name : List.of("conversation-owner", "conversation-other"))
            accountIds.add(users.saveAndFlush(User.builder().email(name+"@example.invalid").nickname(name).password("conversation-unused-password").build()).getId());
    }
    boolean cleanupFixture() {
        try {
            for (long room : rooms) history.delete(accountIds.get(0),room);
            for (long account : accountIds) users.deleteById(account);
            if (fixture!=null) fixture.cleanup();
            assertTrue(accountIds.stream().noneMatch(users::existsById));
            assertTrue(contents.count()==0);
            return true;
        } catch (Exception | AssertionError e) { return false; }
    }
    static List<Map<String,Object>> rows() throws Exception {
        var rows = new ArrayList<Map<String,Object>>();
        for (var story : stories()) for (int t=1;t<=story.get("turns").size();t++) {
            var row = new LinkedHashMap<String,Object>();
            row.put("caseId",story.get("id").asString()); row.put("turn",t);
            row.put("turnId",story.get("id").asString()+"-T"+t); row.put("status","NOT_RUN"); rows.add(row);
        }
        return rows;
    }
    static com.example.recommend.chat.dto.ChatResponse.Usage zero() { return new com.example.recommend.chat.dto.ChatResponse.Usage(0,0,0); }
    static CatalogRationale scriptedRationale(CatalogCandidate work) {
        return new CatalogRationale(List.of(new CatalogRationale.Evidence(CatalogRationale.Field.TITLE,
                work.title().substring(0,Math.min(work.title().length(),30)))),"합성 실행기 검증용 설명이며 의미 품질 점수가 아닙니다.");
    }
    static RecommendationConstraint c(RecommendationConstraint.Kind k,String v,String source) { return new RecommendationConstraint(k,v,source,List.of()); }
    static ChatPlan scriptedPlan(String story,int turn,String question) {
        if (story.equals("DC03")) {
            if (turn==1) return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.CLARIFY,ChatIntent.Media.ANY)),false,List.of(),
                    new Clarification(Clarification.Reason.TV_DURATION_UNIT,"짧게"));
            boolean movie=turn==3;
            return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RECOMMEND,ChatIntent.Media.ANY)),false,List.of(
                    c(RecommendationConstraint.Kind.MEDIA,movie?"MOVIE":"TV",movie?"영화":"시리즈"),
                    c(movie?RecommendationConstraint.Kind.MINUTES_MAX:RecommendationConstraint.Kind.EPISODE_MINUTES_MAX,
                            movie?"100":"30",movie?"100분 이하":"30분 이하")));
        }
        if (story.equals("DC02")) {
            if (turn==3) return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RATIONALE,ChatIntent.Media.ANY,null,null,null,1,0,null)),false);
            if (turn==2) return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.CLARIFY,ChatIntent.Media.ANY)),false);
            return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.RECOMMEND,ChatIntent.Media.ANY)),false,List.of(c(RecommendationConstraint.Kind.MEDIA,"MOVIE","영화")));
        }
        return new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.CLARIFY,ChatIntent.Media.ANY)),false);
    }
    void wire(AiClient delegate, Map<String,Object> row, ChatHistoryStore.Snapshot snapshot, String story,int turn,String question,
              boolean injectFailure) {
        reset(client);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            List<AiMessage> context=call.getArgument(0); assertEquals(snapshot.context(),context); publicOnly(context.toString());
            if (injectFailure) throw ChatException.invalidResponse();
            long start=System.nanoTime();
            var plan=delegate==null?scriptedPlan(story,turn,question):delegate.classify(context,call.getArgument(1));
            row.put("actualPlan",plan); row.put("classificationMs",(System.nanoTime()-start)/1_000_000); return plan;
        }).when(client).classify(anyList(),any());
        doAnswer(call -> {
            row.put("stage","SELECTION");
            CatalogTools original=call.getArgument(1);
            row.put("initialCandidateIds",ids(original.candidates()));
            var events=new ArrayList<Map<String,Object>>(); row.put("toolEvents",events);
            CatalogTools traced=new CatalogTools() {
                public List<CatalogCandidate> candidates(){return original.candidates();}
                public List<RecommendationConstraint> constraints(){return original.constraints();}
                public Map<String,List<Long>> referenceScope(){return original.referenceScope();}
                public boolean privateDataRead(){return original.privateDataRead();}
                public String execute(String name,String args){
                    long start=System.nanoTime();String answer=original.execute(name,args);
                    events.add(Map.of("name",name,"latencyMicros",(System.nanoTime()-start)/1000,"seenCandidateIds",ids(original.candidates())));return answer;
                }
            };
            try {
                CatalogSelection selected;
                if (delegate!=null) selected=delegate.recommendWithTools(call.getArgument(0),traced,call.getArgument(2));
                else {
                    var works=original.candidates().stream().limit(1).toList();
                    var why=new LinkedHashMap<Long,CatalogRationale>();works.forEach(w -> why.put(w.id(),scriptedRationale(w)));
                    selected=new CatalogSelection(ids(works),zero(),why);
                }
                row.put("selectedBeforeValidation",selected.contentIds());row.put("stage","VALIDATION");return selected;
            } finally {row.put("finalCandidateIds",ids(original.candidates()));}
        }).when(client).recommendWithTools(anyList(),any(),any());
        doAnswer(call -> {
            row.put("stage","RATIONALE");row.put("rationaleOriginalRequest",call.getArgument(0));
            CatalogCandidate work=call.getArgument(2);row.put("rationaleTargetIds",List.of(work.id()));
            return delegate==null?scriptedRationale(work):delegate.justify(call.getArgument(0),call.getArgument(1),work,call.getArgument(3));
        }).when(client).justify(anyString(),anyString(),any(),any());
        doAnswer(call -> {
            row.put("stage","RATIONALE");row.put("rationaleOriginalRequest",call.getArgument(0));
            List<CatalogCandidate> works=call.getArgument(2);row.put("rationaleTargetIds",ids(works));
            if(delegate!=null)return delegate.justifyMany(call.getArgument(0),call.getArgument(1),works,call.getArgument(3));
            var why=new LinkedHashMap<Long,CatalogRationale>();works.forEach(w -> why.put(w.id(),scriptedRationale(w)));return why;
        }).when(client).justifyMany(anyString(),anyString(),anyList(),any());
        doAnswer(call -> {row.put("stage","EXPLANATION");
            if(delegate==null)throw ChatException.invalidResponse();return delegate.explain(call.getArgument(0),call.getArgument(1));
        }).when(client).explain(anyString(),any());
    }
    Map<String,Object> run(AiClient delegate,QualityPilotBudget budget,boolean injectFailure) throws Exception {
        var report=new LinkedHashMap<String,Object>();var rows=rows();report.put("results",rows);
        report.put("protocol",protocol());report.put("mode",delegate==null?"SCRIPTED_REHEARSAL_NOT_MODEL_QUALITY":"LIVE_MODEL_PUBLIC_H2_STORED_CONVERSATIONS_NOT_HTTP_OR_BROWSER");
        report.put("applicationWrites",0);report.put("realPrivateRowsRead",0);report.put("providerRequests",0);
        report.put("qualityAccepted",false);report.put("pilotState","STARTED");
        report.put("snapshotFileSha256",sha(OUT.resolve("public-snapshot.json")));
        if(delegate!=null) {
            report.put("model","gpt-6-luna");report.put("maxUsd",liveCap);report.put("priorReservationEstimateUsd",livePrior);
            report.put("runPlanSha256",sha(output().resolve("run-plan.json")));
            report.put("approvedCumulativeUsd",Boolean.getBoolean("qualityConversationV2")?3:1);
            report.put("productClientSha256",sha(Path.of("src/main/java/com/example/recommend/chat/client/OpenAiClient.java")));
            report.put("sharedTurnOutputTokens",2048);report.put("turnTimeoutSeconds",20);
        }
        boolean stop=false;
        try {
            install();
            var snapshot=JSON.readTree(Files.readAllBytes(OUT.resolve("public-snapshot.json")));
            var works=new HashMap<Long,JsonNode>();snapshot.get("rows").forEach(w -> works.put(w.get("id").asLong(),w));
            int index=0;
            for(var story:stories()) {
                String sid=story.get("id").asString();long owner=accountIds.get(0),room=history.create(owner).id();rooms.add(room);
                var failed=new ArrayList<String>();var exclusions=new LinkedHashSet<Long>();
                for(int t=1;t<=4;t++) {
                    var row=rows.get(index++);if(stop)continue;
                    var step=story.get("turns").get(t-1);String question=step.get("user").asString();
                    var before=history.prepare(owner,room,question);
                    var reference=reference(step.get("expected"),before.context(),exclusions);carry(reference,exclusions);
                    row.put("question",question);row.put("expectedState",step.get("expected"));row.put("referenceObservation",reference);
                    row.put("earlierStoryErrorTurnIds",List.copyOf(failed));row.put("contextBefore",before.context());
                    row.put("storedMessagesBefore",before.messageCount());
                    row.put("provenanceBefore",before.provenance().entrySet().stream().map(e -> Map.of("contextIndex",e.getKey(),"ids",e.getValue().ids(),"originalRequest",e.getValue().request())).toList());
                    int initialCalls=budget==null?0:budget.requests;long start=System.nanoTime();
                    row.put("status","STARTED");row.put("stage","INTERPRETATION");
                    wire(delegate,row,before,sid,t,question,injectFailure&&sid.equals("DC01")&&t==1);
                    try {
                        var answer=service.reply(owner,room,question);
                        var after=history.prepare(owner,room,"다음 질문");
                        assertEquals(before.messageCount()+2,after.messageCount());
                        var selected=lastIds(after.context());
                        row.put("answer",answer.messages().get(1).text());row.put("selectedIds",selected);row.put("usage",answer.usage());
                        row.put("hardFactObservation",facts(step.get("expected"),reference,selected,works));
                        row.put("status",selected.isEmpty()?"NO_RECOMMENDATION_REVIEW_REQUIRED":"RETURNED_REVIEW_REQUIRED");row.put("stage","ANSWER");
                    } catch(ChatException ex) {
                        row.put("status",ex.code());failed.add(row.get("turnId").toString());
                        assertEquals(before.messageCount(),history.prepare(owner,room,"다음 질문").messageCount());
                        if(Set.of(429,503,504).contains(ex.status())||(budget!=null&&budget.limitReached))stop=true;
                    } catch(RuntimeException ex) {
                        row.put("status","EVALUATION_ERROR");row.put("errorType",ex.getClass().getSimpleName());stop=true;
                    } finally {
                        row.put("storedMessagesAfter",history.prepare(owner,room,"다음 질문").messageCount());
                        row.put("latencyMs",(System.nanoTime()-start)/1_000_000);
                        row.put("providerRequests",budget==null?0:budget.requests-initialCalls);
                    }
                    if(delegate!=null&&!stop)Thread.sleep(1000);
                }
            }
            report.put("pilotState",stop?"STOPPED":"EXECUTED_REQUIRES_REVIEW");
        } finally {
            report.put("syntheticCleanupSucceeded",cleanupFixture());
            if(budget!=null) {
                report.put("providerRequests",budget.requests);report.put("responsesWithUsage",budget.responsesWithUsage);
                report.put("inputTokens",budget.inputTokens);report.put("outputTokens",budget.outputTokens);
                report.put("requestCharacters",budget.chars);report.put("reservedOutputTokens",budget.reservedOutput);
                report.put("reservationEstimateUsd",QualityPilotBudget.reservedEstimate(budget.requests,budget.chars,budget.reservedOutput));
                report.put("observedUncachedListPriceEstimateUsd",budget.observedListPriceEstimate());report.put("limitReached",budget.limitReached);
                report.put("allRequestsHaveUsage",budget.requests==budget.responsesWithUsage);report.put("billingTotalKnown",false);
                report.put("cumulativeReservationEstimateUsd",livePrior+QualityPilotBudget.reservedEstimate(budget.requests,budget.chars,budget.reservedOutput));
            }
            write(output(),delegate==null?"rehearsal":"pilot",report);
        }
        assertEquals(true,report.get("syntheticCleanupSucceeded"));return report;
    }
    @Test void fixedTwelveTurnsUseActualStoredHistoryWithoutNetwork() throws Exception {
        var report=run(null,null,false);
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)report.get("results");
        assertEquals(12,rows.size());assertTrue(rows.stream().allMatch(r -> r.get("stage").equals("ANSWER")));
        var follow=rows.get(5);var ref=(Map<?,?>)follow.get("referenceObservation");
        assertEquals("MISSING_PREVIOUS_POSITION_2",ref.get("availability"));
        assertEquals(2,follow.get("storedMessagesBefore"));assertEquals(4,follow.get("storedMessagesAfter"));
        assertEquals(0,report.get("providerRequests"));verify(client,never()).reply(anyList());
    }
    @Test void failedTurnIsNotPersistedOrInventedAsFollowupContext() throws Exception {
        var report=run(null,null,true);
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)report.get("results");
        assertEquals("AI_INVALID_RESPONSE",rows.get(0).get("status"));assertEquals(0,rows.get(0).get("storedMessagesAfter"));
        assertEquals(List.of("DC01-T1"),rows.get(1).get("earlierStoryErrorTurnIds"));
        assertEquals(0,rows.get(1).get("storedMessagesBefore"));
    }
    @Test void foreignRoomIsRejectedBeforeProviderWork() throws Exception {
        try {
            install();long room=history.create(accountIds.get(0)).id();rooms.add(room);reset(client);
            assertEquals(404,assertThrows(ChatException.class,()->service.reply(accountIds.get(1),room,"다음 질문")).status());
            verifyNoInteractions(client);assertEquals(0,history.prepare(accountIds.get(0),room,"다음 질문").messageCount());
        } finally {assertTrue(cleanupFixture());}
    }
    @Test void rationaleUsesServerStoredIdsAndOriginalRequestWithoutRecreatingMissingPositions() throws Exception {
        try {
            install();long owner=accountIds.get(0),room=history.create(owner).id();rooms.add(room);
            String original=stories().get(1).get("turns").get(0).get("user").asString();
            var first=new LinkedHashMap<String,Object>();wire(null,first,history.prepare(owner,room,original),"DC02",1,original,false);
            service.reply(owner,room,original);
            String question=stories().get(1).get("turns").get(2).get("user").asString();
            var before=history.prepare(owner,room,question);assertFalse(before.provenance().isEmpty());
            var row=new LinkedHashMap<String,Object>();wire(null,row,before,"DC02",3,question,false);
            service.reply(owner,room,question);
            assertEquals(original,row.get("rationaleOriginalRequest"));assertEquals(lastIds(before.context()),row.get("rationaleTargetIds"));
            assertEquals(4,history.prepare(owner,room,"다음 질문").messageCount());
        } finally {assertTrue(cleanupFixture());}
    }
    @Test @EnabledIfSystemProperty(named="qualityConversationLive",matches="true")
    void actualModelConversationBaseline() throws Exception {
        double cap=Double.parseDouble(QualityBaselineLiveTest.required("CHAT_EVAL_MAX_USD"));
        double prior=Boolean.getBoolean("qualityConversationV2")
                ?QualityConversationV2Plan.validate(Path.of(""),output().resolve("run-plan.json"),cap)
                :QualityConversationPlan.validate(Path.of(""),output().resolve("run-plan.json"),cap);
        livePrior=prior;liveCap=cap;
        assertEquals("gpt-6-luna",QualityBaselineLiveTest.required("CHAT_EVAL_MODEL"));
        var p=new AiProperties();p.setEnabled(true);p.setApiKey(QualityBaselineLiveTest.required("CHAT_EVAL_OPENAI_API_KEY"));
        p.setModel("gpt-6-luna");p.setMaxOutputTokens(2048);p.setTimeoutSeconds(20);p.validate();
        var budget=new QualityPilotBudget(cap);
        var sdk=AiConfig.createSdk(p,"https://api.openai.com/v1");
        try {
            var intercepted=mock(com.openai.client.OpenAIClient.class);var resource=spy(sdk.responses());when(intercepted.responses()).thenReturn(resource);
            doAnswer(call -> {
                ResponseCreateParams params=call.getArgument(0);publicOnly(params.toString());
                budget.reserve(params.toString().length(),params.maxOutputTokens().orElseThrow());
                var response=sdk.responses().create(params,(RequestOptions)call.getArgument(1));
                response.usage().ifPresent(u->{budget.responsesWithUsage++;budget.inputTokens+=u.inputTokens();budget.outputTokens+=u.outputTokens();});return response;
            }).when(resource).create(any(ResponseCreateParams.class),any(RequestOptions.class));
            var report=run(new OpenAiClient(intercepted,p),budget,false);
            assertEquals(0,((List<?>)report.get("results")).stream().filter(r -> ((Map<?,?>)r).get("status").equals("NOT_RUN")).count(),"Incomplete baseline; preserve result, never retry automatically");
        } finally {sdk.close();}
    }
}
