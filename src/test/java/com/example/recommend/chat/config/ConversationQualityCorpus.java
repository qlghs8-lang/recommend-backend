package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic oracle contracts. Replaying these plans is NOT a semantic model evaluation. */
final class ConversationQualityCorpus {
    static final String RESOURCE = "/chat/conversation-quality-v1.json";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ChatResponse.Usage ZERO = new ChatResponse.Usage(0, 0, 0);
    record Fixture(String id, String category, boolean critical, List<AiMessage> context, ChatPlan expected) {
        @Override public String toString() { return id; }
    }
    record Verdict(boolean semantic, boolean executable) {
        boolean passed() { return semantic && executable; }
    }

    static byte[] bytes() throws Exception {
        try (var stream = ConversationQualityCorpus.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Missing evaluation corpus");
            return stream.readAllBytes();
        }
    }
    static String fingerprint() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes()));
    }
    static List<Fixture> load() throws Exception {
        var root = JSON.readTree(new String(bytes(), StandardCharsets.UTF_8));
        if (root.get("version").asInt() != 1) throw new IllegalStateException("Unsupported corpus version");
        var result = new ArrayList<Fixture>();
        for (var row : root.get("cases")) {
            var context = new ArrayList<AiMessage>();
            for (var message : row.get("history")) {
                var ids = new ArrayList<Long>();
                for (var id : message.get("contentIds")) ids.add(id.asLong());
                context.add(new AiMessage(AiMessage.Role.valueOf(message.get("role").asString()),
                        message.get("text").asString(), ids));
            }
            context.add(new AiMessage(AiMessage.Role.USER, row.get("question").asString()));
            result.add(new Fixture(row.get("id").asString(), row.get("category").asString(),
                    row.get("critical").asBoolean(), List.copyOf(context), ChatPlan.parse(row.get("expected").toString())));
        }
        return List.copyOf(result);
    }

    static Verdict evaluate(Fixture fixture, ChatPlan actual) {
        boolean semantic = actual != null && actual.comparison() == fixture.expected().comparison()
                && normalized(actual, fixture.context()).equals(normalized(fixture.expected(), fixture.context()));
        if (actual == null) return new Verdict(false, false);
        // Execute the actual plan through the production span/reference validator. No provider or DB here.
        var client = mock(AiClient.class);
        var recommendations = mock(CatalogRecommendations.class);
        var interactions = mock(ChatInteractionReader.class);
        var details = mock(CatalogDetails.class);
        when(client.classify(anyList(), any())).thenReturn(actual);
        when(client.explain(anyString(), any())).thenReturn(new ChatResponse("Synthetic explanation", ZERO, false));
        when(recommendations.reply(eq(7L), anyList(), anyBoolean(), any(), any()))
                .thenReturn(new ChatResponse("Synthetic recommendation", ZERO, true, List.of(900L)));
        when(recommendations.explainRecommendation(anyLong(), anyString(), anyString(), any())).thenAnswer(call ->
                new ChatResponse("Synthetic rationale", ZERO, true, List.of((Long) call.getArgument(0))));
        when(recommendations.explainRecommendations(anyList(), anyString(), anyString(), any())).thenAnswer(call ->
                new ChatResponse("Synthetic group rationale", ZERO, true, call.getArgument(0)));
        when(interactions.list(eq(7L), any(), any())).thenReturn("본인 반응 목록(답변 시점): Synthetic list");
        when(details.reply(any(), anyString(), any())).thenAnswer(call -> {
            ChatIntent intent = call.getArgument(0);
            return new ChatResponse("Synthetic detail", ZERO, true,
                    List.of(intent.contentId() == null ? (intent.title().equals("달빛 항해") ? 42L : 73L) : intent.contentId()));
        });
        try {
            var response = new ChatRouter(client, recommendations, interactions, details, new AiProperties())
                    .reply(7, fixture.context());
            if (response.message().isBlank()) return new Verdict(semantic, false);
            // A resolved reference must dispatch the trusted ID, including older turns and positions.
            var expectedIds = new ArrayList<Long>();
            for (var intent : actual.requests()) if (intent.referenceTurn() != null) {
                int turn = 0;
                for (int i = fixture.context().size() - 2; i >= 0; i--) {
                    var message = fixture.context().get(i);
                    if (message.role() != AiMessage.Role.ASSISTANT || ++turn != intent.referenceTurn()) continue;
                    int position = intent.referencePosition() == 0 ? 1 : intent.referencePosition();
                    if (position <= message.contentIds().size()) expectedIds.add(message.contentIds().get(position - 1));
                    break;
                }
            }
            if (!expectedIds.isEmpty()) {
                var calledIds = new ArrayList<>(mockingDetails(details).getInvocations().stream()
                        .filter(call -> call.getMethod().getName().equals("reply"))
                        .map(call -> ((ChatIntent) call.getArgument(0)).contentId()).toList());
                mockingDetails(recommendations).getInvocations().stream()
                        .filter(call -> call.getMethod().getName().equals("explainRecommendation"))
                        .forEach(call -> calledIds.add(call.getArgument(0)));
                mockingDetails(recommendations).getInvocations().stream()
                        .filter(call -> call.getMethod().getName().equals("explainRecommendations"))
                        .forEach(call -> calledIds.addAll(call.getArgument(0)));
                if (!calledIds.containsAll(expectedIds))
                    return new Verdict(semantic, false);
            }
            if (actual.requests().stream().anyMatch(i -> i.route().name().startsWith("LIST_"))
                    && !response.contentIds().isEmpty()) return new Verdict(semantic, false);
            return new Verdict(semantic, true);
        } catch (ChatException e) {
            return new Verdict(semantic, false);
        }
    }

    private static List<ChatIntent> normalized(ChatPlan plan, List<AiMessage> context) {
        // Ignore whitespace at split boundaries; the router additionally checks lossless ordered spans.
        // For a unique work, positions 0 and 1 are equivalent; invalid singular references still fail semantic matching.
        return plan.requests().stream().map(i -> new ChatIntent(i.route(), i.media(), i.title(), i.contentId(),
                i.releaseYear(), i.referenceTurn(), i.referencePosition() != null && i.referencePosition() == 0
                && hasSingleWork(context, i.referenceTurn())
                ? Integer.valueOf(1) : i.referencePosition(), plan.requests().size() == 2 && !plan.comparison() && i.question() != null
                ? i.question().replaceAll("\\s+", "") : null)).toList();
    }

    private static boolean hasSingleWork(List<AiMessage> context, int requestedTurn) {
        int turn = 0;
        for (int index = context.size() - 2; index >= 0; index--) {
            var message = context.get(index);
            if (message.role() == AiMessage.Role.ASSISTANT && ++turn == requestedTurn)
                return message.contentIds().size() == 1;
        }
        return false;
    }
}
