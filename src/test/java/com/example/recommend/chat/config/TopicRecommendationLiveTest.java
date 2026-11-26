package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen topic cases; actual classification is opt-in, <=18 requests, no application DB or generation. */
class TopicRecommendationLiveTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static byte[] bytes() throws Exception {
        try (var stream = TopicRecommendationLiveTest.class.getResourceAsStream("/chat/topic-recommendation-v1.json")) {
            if (stream == null) throw new IllegalStateException("Missing fixed topic cases");
            return stream.readAllBytes();
        }
    }
    static List<AiMessage> context(JsonNode test) {
        var messages = new ArrayList<AiMessage>();
        if (!test.get("prior").isNull()) {
            messages.add(new AiMessage(AiMessage.Role.USER, test.get("prior").asString()));
            messages.add(new AiMessage(AiMessage.Role.ASSISTANT, "합성 공개 요리 작품을 추천했어요.", List.of(42L)));
        }
        messages.add(new AiMessage(AiMessage.Role.USER, test.get("question").asString()));
        return List.copyOf(messages);
    }
    static List<String> issues(JsonNode test, ChatPlan plan) {
        var issues = new ArrayList<String>();
        if (plan.comparison() || plan.requests().size()!=1 || plan.requests().get(0).route()!=ChatIntent.Route.RECOMMEND)
            issues.add("RECOMMEND_REQUIRED");
        var topics = plan.constraints().stream().filter(c->c.kind()==RecommendationConstraint.Kind.TOPIC).toList();
        if(topics.size()!=1) issues.add("ONE_TOPIC_REQUIRED");
        else {
            var topic=topics.get(0);
            boolean reset=test.get("id").asString().equals("topic-reset");
            var allowed=reset?Set.of("우주","행성","우주선"):Set.of("요리","조리","셰프","쉐프","요리사","주방","레스토랑");
            if(topic.terms().stream().noneMatch(allowed::contains)) issues.add("PRECISE_TOPIC_CUE_REQUIRED");
            if(reset && topic.terms().stream().anyMatch(Set.of("요리","셰프","주방")::contains)) issues.add("STALE_TOPIC");
        }
        if(plan.constraints().stream().noneMatch(c->c.kind()==RecommendationConstraint.Kind.MEDIA && c.value().equals("MOVIE"))) issues.add("MOVIE_REQUIRED");
        if(plan.constraints().stream().anyMatch(c->c.kind()==RecommendationConstraint.Kind.UNVERIFIABLE || c.kind()==RecommendationConstraint.Kind.AVOID)) issues.add("EXPLICIT_TOPIC_WAS_BLOCKED");
        if(!test.get("minutes").isNull()) {
            if(plan.constraints().stream().noneMatch(c->c.kind()==RecommendationConstraint.Kind.MINUTES_MAX && c.value().equals("90"))) issues.add("TIME_BOUND_REQUIRED");
            if(plan.constraints().stream().noneMatch(c->c.kind()==RecommendationConstraint.Kind.REFERENCE && c.value().equals("ONLY_LAST"))) issues.add("REFERENCE_REQUIRED");
        }
        try {
            var messages=context(test);
            if(RecommendationCriteria.from(plan.constraints(),messages,messages.get(messages.size()-1).text()).clarification()!=null) issues.add("UNEXPECTED_CLARIFICATION");
        } catch(RuntimeException e) { issues.add("INVALID_USER_SOURCE"); }
        return issues;
    }
    @Test void fixedTopicContractsParseAndRejectMissingThemesOrUnverifiableExampleSubstitutions() throws Exception {
        var corpus=JSON.readTree(new String(bytes(),StandardCharsets.UTF_8)); assertEquals(6,corpus.get("cases").size());
        for(var test:corpus.get("cases")) {
            var constraints=new ArrayList<Map<String,Object>>();
            constraints.add(Map.of("kind","TOPIC","value","topic","source",test.get("topicSource").asString(),"terms",JSON.convertValue(test.get("topicTerms"),List.class)));
            if(!test.get("minutes").isNull()) {
                constraints.add(Map.of("kind","MINUTES_MAX","value","90","source","90분 이하","terms",List.of()));
                constraints.add(Map.of("kind","REFERENCE","value","ONLY_LAST","source","그중","terms",List.of()));
            }
            String wire=JSON.writeValueAsString(Map.of("composition",Map.of("requests",List.of(Map.of("route","RECOMMEND","media","MOVIE","mediaSource","영화")),"comparison",false),"constraints",constraints));
            // composition requires an explicit null boundary, including for one operation.
            wire=wire.replace("\"comparison\":false","\"comparison\":false,\"splitAfter\":null");
            var plan=ClassifierPlan.parseProvider(wire,context(test)); assertEquals(List.of(),issues(test,plan),test.get("id").asString());
            assertFalse(issues(test,ChatPlan.single(new ChatIntent(ChatIntent.Route.RECOMMEND,ChatIntent.Media.ANY))).isEmpty());
            assertFalse(issues(test,new ChatPlan(plan.requests(),false,List.of(new RecommendationConstraint(
                    RecommendationConstraint.Kind.UNVERIFIABLE,"similarity",test.get("topicSource").asString(),List.of())))).isEmpty());
        }
    }
    @Test @EnabledIfSystemProperty(named="chatTopicLive",matches="true")
    void actualClassifierUsesExplicitSubjectsEvenWithNamedExamples() throws Exception {
        var p=new AiProperties();p.setEnabled(true);p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY"));p.setModel(System.getenv("CHAT_EVAL_MODEL"));p.validate();
        var cases=JSON.readTree(new String(bytes(),StandardCharsets.UTF_8)).get("cases");
        var rows=new ArrayList<Map<String,Object>>();int requests=0;boolean stopped=false;var started=Instant.now();
        try(var client=new AiConfig().aiClient(p)) {
            for(int repeat=1;repeat<=3;repeat++) for(var test:cases) {
                var row=new LinkedHashMap<String,Object>();rows.add(row);row.put("id",test.get("id").asString());row.put("repeat",repeat);row.put("status","NOT_RUN");
                if(stopped) continue;
                var budget=new TurnBudget(p);
                try {
                    assertTrue(++requests<=18);var plan=client.classify(context(test),budget);var found=issues(test,plan);
                    row.put("plan",plan);row.put("issues",found);row.put("status",found.isEmpty()?"PASS":"MISMATCH");
                } catch(ChatException e) { row.put("status",e.code());stopped=e.status()==429||e.status()==503||e.status()==504; }
                row.put("usage",budget.usage());if(!stopped)Thread.sleep(1000);
            }
        } finally {
            var directory=Path.of("target/topic-recommendation-20261124");Files.createDirectories(directory);
            Files.writeString(directory.resolve("classification-live-"+started.toString().replaceAll("[^0-9]","")+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "evaluation","topic-classification-only-not-generation-quality","model",p.getModel(),"requests",requests,
                    "corpusSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes())),"results",rows)));
        }
        assertEquals(18,rows.size());assertTrue(rows.stream().allMatch(r->r.get("status").equals("PASS")),"Preserve initial topic classification failure report");
    }
}
