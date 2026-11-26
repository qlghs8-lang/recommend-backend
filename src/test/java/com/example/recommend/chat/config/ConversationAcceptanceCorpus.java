package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A versioned policy overlay, never a rewrite of the frozen v1 oracle. Synthetic data only. */
final class ConversationAcceptanceCorpus {
    static final String RESOURCE = "/chat/conversation-acceptance-v1.json";
    static final String ORIGINAL_HASH = "44f9cb9533dfb6bf6fe74a16069a1bceedfcc67f305b6a29e9a71c77467b1926";
    static JsonNode root() throws Exception {
        try (var in = ConversationAcceptanceCorpus.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing acceptance corpus");
            var root = ConversationQualityCorpus.JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (root.get("version").asInt() != 1 || !root.get("sourceCorpusSha256").asString().equals(ORIGINAL_HASH))
                throw new IllegalStateException("Unexpected acceptance version or provenance");
            return root;
        }
    }
    static String fingerprint() throws Exception {
        try (var in = ConversationAcceptanceCorpus.class.getResourceAsStream(RESOURCE)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(in).readAllBytes()));
        }
    }
    static ChatPlan expected(JsonNode step, List<AiMessage> context) {
        return ClassifierPlan.parseProvider(step.get("provider").toString(), context);
    }
    static List<String> planIssues(ChatPlan expected, ChatPlan actual, JsonNode optional) {
        if (actual == null) return List.of("MISSING_PLAN");
        var issues = new ArrayList<String>();
        if (expected.comparison() != actual.comparison() || !normalized(expected).equals(normalized(actual))) issues.add("OPERATIONS");
        if (!Objects.equals(expected.clarification() == null ? null : expected.clarification().reason(),
                actual.clarification() == null ? null : actual.clarification().reason())) issues.add("CLARIFICATION_REASON");
        var remaining = new ArrayList<>(actual.constraints());
        for (var constraint : expected.constraints()) {
            var matches = remaining.stream().filter(c -> c.kind() == constraint.kind()
                    && (c.kind() == RecommendationConstraint.Kind.UNVERIFIABLE || c.value().equals(constraint.value()))).toList();
            if (matches.size() != 1) issues.add("CONSTRAINT_" + constraint.kind());
            else remaining.remove(matches.get(0));
        }
        for (var extra : remaining) {
            boolean allowed = optional != null && optional.valueStream().anyMatch(o -> o.get("kind").asString().equals(extra.kind().name())
                    && o.get("value").asString().equals(extra.value()));
            if (!allowed) issues.add("EXTRA_CONSTRAINT");
        }
        return List.copyOf(issues);
    }
    private static List<ChatIntent> normalized(ChatPlan plan) {
        return plan.requests().stream().map(i -> new ChatIntent(i.route(), i.media(), i.title(), i.contentId(), i.releaseYear(),
                i.referenceTurn(), i.referencePosition() != null && i.referencePosition() == 0 ? Integer.valueOf(1) : i.referencePosition(), null)).toList();
    }
    static List<String> revisedIssues(ConversationQualityCorpus.Fixture fixture, ChatPlan actual) throws Exception {
        var override = root().get("overrides").get(fixture.id());
        if (override == null) {
            var issues = new ArrayList<String>();
            if (!ConversationQualityCorpus.evaluate(fixture, actual).passed()) issues.add("FROZEN_V1_CONTRACT");
            if (actual != null && actual.clarification() != null) issues.add("UNEXPECTED_REASON");
            return List.copyOf(issues);
        }
        String current = fixture.context().get(fixture.context().size() - 1).text();
        var expected = new ChatPlan(List.of(new ChatIntent(ChatIntent.Route.CLARIFY, ChatIntent.Media.ANY)), false, List.of(),
                new Clarification(Clarification.Reason.valueOf(override.asString()), current));
        var issues = new ArrayList<>(planIssues(expected, actual, null));
        if (actual != null) {
            try { if (actual.clarification() != null) actual.clarification().validate(current); }
            catch (RuntimeException e) { issues.add("INVALID_CURRENT_SOURCE"); }
            if (!ConversationQualityCorpus.evaluate(new ConversationQualityCorpus.Fixture(fixture.id(), fixture.category(),
                    fixture.critical(), fixture.context(), expected), actual).executable()) issues.add("ROUTER_REJECTED");
        }
        return List.copyOf(issues);
    }
}
