package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.ChatException;
import com.openai.core.RequestOptions;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.ResponseCreateParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fixed public synthetic animation records only. No application config, database or private chat. */
@EnabledIfSystemProperty(named = "chatAvailabilityLive", matches = "true")
class RecommendationAvailabilityLiveTest {
    @Test void separatesGreetingClassificationAndTwelveCandidateRecommendationFailures() throws Exception {
        var p = new AiProperties(); p.setEnabled(true); p.setMaxOutputTokens(2048);
        String forcedOrder = System.getProperty("chatAvailabilityForceOrder", "none");
        if (!Set.of("none", "contentId", "evidence", "interpretation").contains(forcedOrder))
            throw new IllegalArgumentException("Unknown fixed schema order probe");
        p.setApiKey(System.getenv("CHAT_EVAL_OPENAI_API_KEY")); p.setModel(System.getenv("CHAT_EVAL_MODEL")); p.validate();
        var trace = new ArrayList<Map<String, Object>>(); var results = new LinkedHashMap<String, Object>();
        var phase = new String[] {"greeting"}; var started = Instant.now();
        var sdk = AiConfig.createSdk(p, "https://api.openai.com/v1");
        var intercepted = mock(com.openai.client.OpenAIClient.class); var resource = spy(sdk.responses());
        when(intercepted.responses()).thenReturn(resource);
        doAnswer(call -> {
            var item = new LinkedHashMap<String, Object>(); trace.add(item); item.put("phase", phase[0]);
            ResponseCreateParams params = call.getArgument(0);
            if (phase[0].equals("recommendation-generation")) {
                var format = params.text().orElseThrow().format().orElseThrow().asJsonSchema();
                var schema = format.schema();
                var props = schema._additionalProperties().get("properties").convert(Map.class);
                var rec = (Map<?, ?>) props.get("recommendations");
                var variants = (List<?>) ((Map<?, ?>) rec.get("items")).get("anyOf");
                for (var variant : variants) {
                    var entry = (Map<String, Object>) variant;
                    var fields = (Map<String, Object>) entry.get("properties");
                    if (!forcedOrder.equals("none")) {
                        var ordered = new LinkedHashMap<String, Object>(); ordered.put(forcedOrder, fields.get(forcedOrder));
                        fields.forEach(ordered::putIfAbsent); entry.put("properties", ordered);
                    }
                }
                item.put("recommendationFieldOrder", ((Map<?, ?>) ((Map<?, ?>) variants.get(0)).get("properties")).keySet());
                var updated = schema.toBuilder().putAdditionalProperty("properties", com.openai.core.JsonValue.from(props)).build();
                params = params.toBuilder().text(com.openai.models.responses.ResponseTextConfig.builder()
                        .format(format.toBuilder().schema(updated).build()).build()).build();
            }
            try {
                var response = sdk.responses().create(params, (RequestOptions) call.getArgument(1));
                item.put("status", response.status().map(Object::toString).orElse("missing"));
                response.usage().ifPresent(u -> { item.put("inputTokens", u.inputTokens()); item.put("outputTokens", u.outputTokens()); });
                return response;
            } catch (OpenAIServiceException e) {
                item.put("providerHttpStatus", e.statusCode());
                item.put("providerCode", e.code().orElse("missing")); item.put("providerParam", e.param().orElse("missing"));
                // Only 400 diagnostics from this fixed synthetic request. Never dump headers, exception or key.
                if (e.statusCode() == 400) {
                    var body = e.body().convert(Map.class);
                    if (body.get("error") instanceof Map<?, ?> error) body = error;
                    if (body.get("message") instanceof String message)
                        item.put("syntheticSchemaError", message.substring(0, Math.min(1600, message.length())).replace(p.getApiKey(), "[redacted]"));
                }
                throw e;
            }
        }).when(resource).create(any(ResponseCreateParams.class), any(RequestOptions.class));
        var client = new OpenAiClient(intercepted, p);
        try {
            results.put("greeting", client.classify(List.of(new AiMessage(AiMessage.Role.USER, "안녕")), new TurnBudget(p)));
            phase[0] = "recommendation-classification";
            var context = List.of(new AiMessage(AiMessage.Role.USER, "안녕"), new AiMessage(AiMessage.Role.ASSISTANT, "안녕하세요!"),
                    new AiMessage(AiMessage.Role.USER, "애니메이션 추천 좀"));
            var budget = new TurnBudget(p); var plan = client.classify(context, budget); results.put("classification", plan);
            assertEquals(ChatIntent.Route.RECOMMEND, plan.requests().get(0).route());
            var candidates = new ArrayList<CatalogCandidate>();
            for (int i = 1; i <= 12; i++) candidates.add(new CatalogCandidate(i, "합성 모험 " + i,
                    i % 2 == 0 ? "TV" : "MOVIE", "animation,family,adventure", "2020-01-01",
                    "친구들이 낯선 별을 탐험하며 가족과 우정의 의미를 배우는 애니메이션 이야기. ".repeat(7)));
            CatalogTools tools = new CatalogTools() {
                int calls;
                public List<CatalogCandidate> candidates() { return List.copyOf(candidates); }
                public List<RecommendationConstraint> constraints() { return plan.constraints(); }
                public String execute(String name, String args) {
                    if (++calls > 3) throw ChatException.invalidResponse();
                    var data = ConversationQualityCorpus.JSON.readTree(args);
                    return switch (name) {
                        case "search_catalog" -> ConversationQualityCorpus.JSON.writeValueAsString(candidates);
                        case "get_content" -> ConversationQualityCorpus.JSON.writeValueAsString(Map.of("content", candidates.stream()
                                .filter(c -> c.id() == data.get("contentId").asLong()).findFirst().orElseThrow()));
                        default -> throw ChatException.invalidResponse();
                    };
                }
            };
            phase[0] = "recommendation-generation";
            var selection = client.recommendWithTools(context, tools, budget);
            results.put("selectedIds", selection.contentIds()); results.put("status", "PASS");
            assertFalse(selection.contentIds().isEmpty());
        } catch (ChatException e) { results.put("status", e.code()); }
        finally {
            sdk.close(); Files.createDirectories(Path.of("target/chat-quality"));
            Files.writeString(Path.of("target/chat-quality/availability-" + started.toString().replaceAll("[^0-9]", "") + ".json"),
                    ConversationQualityCorpus.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "evaluation", "fixed-synthetic-availability-diagnostic", "model", p.getModel(),
                            "forcedOrderProbe", forcedOrder, "trace", trace, "results", results)));
        }
        assertEquals("PASS", results.get("status"), "Inspect synthetic availability report");
    }
}
