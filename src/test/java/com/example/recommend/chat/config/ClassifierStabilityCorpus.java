package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.RecommendationCriteria;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Frozen synthetic extraction cases, separate from the unchanged 61-case v1 oracle. */
final class ClassifierStabilityCorpus {
    static final String RESOURCE = "/chat/classifier-stability-v1.json";
    record Expected(String kind, String value) { }
    record Case(String id, List<AiMessage> context, List<String> routes, List<Expected> constraints) {
        @Override public String toString() { return id; }
    }
    static byte[] bytes() throws Exception {
        try (var stream = ClassifierStabilityCorpus.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Missing frozen stability corpus");
            return stream.readAllBytes();
        }
    }
    static String fingerprint() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes()));
    }
    static List<Case> load() throws Exception {
        var root = ConversationQualityCorpus.JSON.readTree(new String(bytes(), StandardCharsets.UTF_8));
        if (root.path("version").asInt() != 1 || root.path("repeats").asInt() != 3
                || root.path("totalOutputTokens").asInt() != 2048 || root.path("timeoutSeconds").asInt() != 20)
            throw new IllegalStateException("Unexpected frozen evaluation limits");
        var result = new ArrayList<Case>();
        for (var row : root.get("cases")) {
            var context = new ArrayList<AiMessage>();
            for (var message : row.get("history")) {
                var ids = new ArrayList<Long>();
                for (var id : message.get("contentIds")) ids.add(id.asLong());
                context.add(new AiMessage(AiMessage.Role.valueOf(message.get("role").asString()), message.get("text").asString(), ids));
            }
            context.add(new AiMessage(AiMessage.Role.USER, row.get("question").asString()));
            var routes = new ArrayList<String>(); for (var route : row.get("expectedRoutes")) routes.add(route.asString());
            var conditions = new ArrayList<Expected>();
            for (var c : row.get("expectedConstraints")) conditions.add(new Expected(c.get(0).asString(), c.get(1).asString()));
            result.add(new Case(row.get("id").asString(), List.copyOf(context), List.copyOf(routes), List.copyOf(conditions)));
        }
        return List.copyOf(result);
    }
    static List<String> issues(Case test, ChatPlan plan) {
        var issues = new ArrayList<String>();
        if (plan == null) return List.of("MISSING_PLAN");
        if (plan.comparison() || !test.routes().equals(plan.requests().stream().map(i -> i.route().name()).toList()))
            issues.add("ROUTE_OR_ORDER");
        if (plan.constraints().size() != test.constraints().size()) issues.add("CONSTRAINT_COUNT");
        for (var expected : test.constraints()) {
            long matches = plan.constraints().stream().filter(c -> c.kind().name().equals(expected.kind())
                    && (expected.value().equals("*") || c.value().equals(expected.value()))).count();
            if (matches != 1) issues.add("CONSTRAINT_" + expected.kind());
        }
        for (var intent : plan.requests()) if (intent.route() == ChatIntent.Route.RECOMMEND || intent.route() == ChatIntent.Route.PERSONALIZED) {
            String question = intent.question() == null ? test.context().get(test.context().size() - 1).text() : intent.question();
            try {
                if (RecommendationCriteria.from(plan.constraints(), test.context(), question).clarification() != null)
                    issues.add("CRITERIA_CLARIFICATION");
            } catch (RuntimeException e) { issues.add("INVALID_CONSTRAINT_SOURCE"); }
        }
        var fixture = new ConversationQualityCorpus.Fixture(test.id(), "stability", true, test.context(), plan);
        if (!ConversationQualityCorpus.evaluate(fixture, plan).executable()) issues.add("ROUTER_REJECTED");
        return List.copyOf(issues);
    }
}
