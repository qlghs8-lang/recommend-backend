package com.example.recommend.chat.client;

import com.example.recommend.chat.service.ChatException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Bounded read-only plan, independently validated before executing any operation. */
public record ChatPlan(List<ChatIntent> requests, boolean comparison, List<RecommendationConstraint> constraints,
        Clarification clarification) {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<ChatIntent.Route> COMPOSABLE = Set.of(ChatIntent.Route.DETAIL,
            ChatIntent.Route.RECOMMEND, ChatIntent.Route.PERSONALIZED, ChatIntent.Route.LIST_LIKE,
            ChatIntent.Route.LIST_DISLIKE, ChatIntent.Route.LIST_BOOKMARK, ChatIntent.Route.EXPLAIN, ChatIntent.Route.RATIONALE);

    public ChatPlan(List<ChatIntent> requests, boolean comparison) { this(requests, comparison, List.of()); }
    public ChatPlan(List<ChatIntent> requests, boolean comparison, List<RecommendationConstraint> constraints) {
        this(requests, comparison, constraints, null);
    }

    public ChatPlan {
        if (requests == null || requests.isEmpty() || requests.size() > 2 || requests.stream().anyMatch(Objects::isNull))
            throw ChatException.invalidResponse();
        requests = List.copyOf(requests);
        if (constraints == null || constraints.size() > 8 || constraints.stream().anyMatch(Objects::isNull)
                || new HashSet<>(constraints).size() != constraints.size()) throw ChatException.invalidResponse();
        constraints = List.copyOf(constraints);
        if (!constraints.isEmpty() && requests.stream().noneMatch(r -> r.route() == ChatIntent.Route.RECOMMEND
                || r.route() == ChatIntent.Route.PERSONALIZED)) throw ChatException.invalidResponse();
        if (requests.size() > 1 && (requests.stream().anyMatch(r -> !COMPOSABLE.contains(r.route()))
                || requests.stream().filter(r -> r.route() == ChatIntent.Route.RECOMMEND
                || r.route() == ChatIntent.Route.PERSONALIZED || r.route() == ChatIntent.Route.EXPLAIN
                || r.route() == ChatIntent.Route.RATIONALE).count() > 1)) throw ChatException.invalidResponse();
        if (comparison && (requests.size() != 2 || requests.stream().anyMatch(r -> r.route() != ChatIntent.Route.DETAIL)))
            throw ChatException.invalidResponse();
        if (clarification != null && (requests.size() != 1 || comparison || requests.get(0).route() != ChatIntent.Route.CLARIFY))
            throw ChatException.invalidResponse();
    }

    public static ChatPlan single(ChatIntent intent) { return new ChatPlan(List.of(intent), false); }

    public static ChatPlan parse(String text) {
        try {
            if (text == null || text.length() > 32000) throw ChatException.invalidResponse();
            var root = JSON.readTree(text);
            // The immutable v1 evaluation corpus predates constraints; the provider path requires the new field.
            if (!root.isObject() || (root.size() != 2 && root.size() != 3 && root.size() != 4) || !root.has("requests") || !root.has("comparison")
                    || !root.get("requests").isArray() || !root.get("comparison").isBoolean())
                throw ChatException.invalidResponse();
            if (root.size() >= 3 && (!root.has("constraints") || !root.get("constraints").isArray())) throw ChatException.invalidResponse();
            if (root.size() == 4 && !root.has("clarification")) throw ChatException.invalidResponse();
            var requests = new ArrayList<ChatIntent>();
            for (var node : root.get("requests")) requests.add(ChatIntent.parse(node.toString()));
            var constraints = new ArrayList<RecommendationConstraint>();
            if (root.has("constraints")) for (var node : root.get("constraints")) constraints.add(RecommendationConstraint.parse(node));
            var clarification = root.has("clarification") && !root.get("clarification").isNull()
                    ? Clarification.parse(root.get("clarification")) : null;
            return new ChatPlan(requests, root.get("comparison").asBoolean(), constraints, clarification);
        } catch (RuntimeException e) { throw ChatException.invalidResponse(); }
    }
}
