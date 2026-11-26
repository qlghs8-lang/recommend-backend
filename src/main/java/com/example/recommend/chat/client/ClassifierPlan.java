package com.example.recommend.chat.client;

import com.example.recommend.chat.service.ChatException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Compact provider wire format. Only the server reconstructs executable question spans. */
public final class ClassifierPlan {
    private ClassifierPlan() { }
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> DETAIL = Set.of("route", "media", "title", "contentId", "releaseYear", "referenceTurn", "referencePosition");
    private static final Set<String> REFERENCE = Set.of("route", "referenceTurn", "referencePosition");
    private static final Set<String> LIST = Set.of("route", "media");
    private static final Set<String> RECOMMENDATION = Set.of("route", "media", "mediaSource");

    private static boolean recommendation(ChatIntent.Route route) {
        return route == ChatIntent.Route.RECOMMEND || route == ChatIntent.Route.PERSONALIZED;
    }

    public static Map<String, Object> operationSchema() {
        return operationSchema(false);
    }

    private static Map<String, Object> operationSchema(boolean compound) {
        var route = Map.<String, Object>of("type", "string", "enum", Arrays.stream(ChatIntent.Route.values())
                .filter(r -> r != ChatIntent.Route.DETAIL && r != ChatIntent.Route.RATIONALE
                        && r != ChatIntent.Route.CLARIFY && !recommendation(r) && !r.name().startsWith("LIST_"))
                .filter(r -> !compound || r == ChatIntent.Route.EXPLAIN)
                .map(Enum::name).toList());
        var variants = new ArrayList<>(List.of(
                object(Map.of("route", route)),
                object(Map.of("route", Map.of("type", "string", "enum", List.of("LIST_LIKE", "LIST_DISLIKE", "LIST_BOOKMARK")),
                        "media", Map.of("type", "string", "enum", List.of("ANY", "MOVIE", "TV")))),
                detailOperationSchema(),
                object(Map.of("route", Map.of("type", "string", "enum", List.of("RATIONALE")),
                        "referenceTurn", Map.of("type", "integer", "minimum", 1, "maximum", 6),
                        "referencePosition", Map.of("type", "integer", "minimum", 0, "maximum", 5))),
                object(Map.of("route", Map.of("type", "string", "enum", List.of("RECOMMEND", "PERSONALIZED")),
                        "media", Map.of("type", "string", "enum", List.of("ANY", "MOVIE", "TV")),
                        "mediaSource", Map.of("type", List.of("string", "null"), "minLength", 1, "maxLength", 100)))));
        if (!compound) {
            var reasons = new ArrayList<Object>(Arrays.stream(Clarification.Reason.values()).map(Enum::name).toList());
            reasons.add(null); // General clarification is an explicit decision, never a missing field.
            variants.add(object(Map.of("route", Map.of("type", "string", "enum", List.of("CLARIFY")),
                    "reason", Map.of("type", List.of("string", "null"), "enum", reasons),
                    "source", Map.of("type", List.of("string", "null"), "minLength", 1, "maxLength", 100))));
        }
        return Map.of("anyOf", variants);
    }

    private static Map<String, Object> detailOperationSchema() {
        return object(Map.of("route", Map.of("type", "string", "enum", List.of("DETAIL")),
                "media", Map.of("type", "string", "enum", List.of("ANY", "MOVIE", "TV")),
                "title", Map.of("type", List.of("string", "null"), "maxLength", 200),
                "contentId", Map.of("type", List.of("integer", "null"), "minimum", 1),
                "releaseYear", Map.of("type", List.of("integer", "null"), "minimum", 1800, "maximum", 2200),
                "referenceTurn", Map.of("type", List.of("integer", "null"), "minimum", 1, "maximum", 6),
                "referencePosition", Map.of("type", List.of("integer", "null"), "minimum", 0, "maximum", 5)));
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        // Structured decoding follows property order. Every union branch must decide route FIRST.
        // Map.of iteration order can vary between JVMs and bias branch selection before route is emitted.
        var ordered = new LinkedHashMap<String, Object>();
        for (String field : List.of("route", "media", "mediaSource", "title", "contentId", "releaseYear", "referenceTurn", "referencePosition", "reason", "source"))
            if (properties.containsKey(field)) ordered.put(field, properties.get(field));
        return Map.of("type", "object", "properties", ordered,
                "required", List.copyOf(ordered.keySet()), "additionalProperties", false);
    }

    /** Short verbatim suffixes uniquely identify a boundary; no semantic/route keyword rules. */
    static Map<String, Integer> boundaries(String current) {
        if (current == null || current.isBlank() || current.length() > 8000) throw ChatException.invalidResponse();
        var result = new LinkedHashMap<String, Integer>();
        for (int end = 1; end < current.length(); end++) {
            char previous = current.charAt(end - 1);
            if (Character.isWhitespace(previous) || Character.isLowSurrogate(current.charAt(end))) continue;
            if (!Character.isWhitespace(current.charAt(end)) && ".!?;。！？；".indexOf(previous) < 0) continue;
            if (current.substring(end).isBlank()) continue;
            int start = end;
            // Prefer a whole whitespace-delimited word; extend left only when it is repeated.
            while (start > 0 && !Character.isWhitespace(current.charAt(start - 1))) start--;
            // The provider rejects raw double quotes inside strict enum string literals.
            // Keep a verbatim suffix after the last quote, never replace characters in the user's text.
            int quote = current.lastIndexOf('"', end - 1);
            if (quote >= start) start = quote + 1;
            if (start == end) continue;
            while (start >= 0 && end - start <= 100) {
                String anchor = current.substring(start, end);
                if (anchor.indexOf('"') >= 0) break;
                if (!anchor.isBlank() && current.indexOf(anchor) == start && current.lastIndexOf(anchor) == start) {
                    result.put(anchor, end); break;
                }
                if (start == 0) break;
                start = current.offsetByCodePoints(start, -1);
            }
            // Keep the dynamic enum bounded. Long single requests are still supported.
            if (result.size() > 128) return Map.of();
        }
        return result;
    }

    public static Map<String, Object> splitSchema(String current) {
        var options = new ArrayList<Object>(); options.add(null); options.addAll(boundaries(current).keySet());
        return Map.of("type", List.of("string", "null"), "enum", options);
    }

    private enum Composition { SINGLE, COMPARISON, INDEPENDENT }

    /** Couple operation count/type, comparison and boundary; root-level anyOf is unsupported. */
    public static Map<String, Object> compositionSchema(String current) {
        var variants = new ArrayList<Map<String, Object>>();
        var anchors = List.copyOf(boundaries(current).keySet());
        for (var composition : Composition.values()) {
            boolean comparison = composition == Composition.COMPARISON;
            boolean independent = composition == Composition.INDEPENDENT;
            if (independent && anchors.isEmpty()) continue;
            int count = composition == Composition.SINGLE ? 1 : 2;
            var fields = new LinkedHashMap<String, Object>();
            fields.put("requests", Map.of("type", "array", "minItems", count, "maxItems", count,
                    "items", comparison ? detailOperationSchema() : operationSchema(independent)));
            fields.put("comparison", Map.of("type", "boolean", "enum", List.of(comparison)));
            fields.put("splitAfter", independent ? Map.of("type", "string", "enum", anchors) : Map.of("type", "null"));
            variants.add(Map.of("type", "object", "properties", fields,
                    "required", List.of("requests", "comparison", "splitAfter"), "additionalProperties", false));
        }
        return Map.of("anyOf", variants);
    }

    /** Strict current provider contract; translate structure only, never repair values or ignore fields. */
    public static ChatPlan parseProvider(String text, List<AiMessage> context) {
        try {
            if (text == null || text.length() > 32000) throw ChatException.invalidResponse();
            var root = JSON.readTree(text);
            requireFields(root, Set.of("composition", "constraints"));
            var composition = root.get("composition");
            requireFields(composition, Set.of("requests", "comparison", "splitAfter"));
            var normalized = JSON.createObjectNode();
            normalized.set("requests", composition.get("requests"));
            normalized.set("comparison", composition.get("comparison"));
            normalized.set("splitAfter", composition.get("splitAfter"));
            normalized.set("constraints", root.get("constraints"));
            return parse(normalized.toString(), context);
        } catch (RuntimeException e) { throw ChatException.invalidResponse(); }
    }

    /** Flat compact representation retained for historical synthetic replays, not the live provider entry point. */
    public static ChatPlan parse(String text, String current) {
        return parse(text, List.of(new AiMessage(AiMessage.Role.USER, current)));
    }

    public static ChatPlan parse(String text, List<AiMessage> context) {
        try {
            if (context == null || context.isEmpty() || context.get(context.size() - 1).role() != AiMessage.Role.USER)
                throw ChatException.invalidResponse();
            String current = context.get(context.size() - 1).text();
            if (text == null || text.length() > 32000) throw ChatException.invalidResponse();
            var root = JSON.readTree(text);
            requireFields(root, Set.of("requests", "comparison", "splitAfter", "constraints"));
            if (!root.get("requests").isArray() || !root.get("comparison").isBoolean()
                    || !root.get("constraints").isArray()) throw ChatException.invalidResponse();
            int count = root.get("requests").size();
            boolean comparison = root.get("comparison").asBoolean();
            var split = root.get("splitAfter");
            Integer boundary = null;
            if (count == 2 && !comparison) {
                if (!split.isString() || (boundary = boundaries(current).get(split.asString())) == null)
                    throw ChatException.invalidResponse();
            } else if (!split.isNull()) throw ChatException.invalidResponse();
            var operations = new ArrayList<ChatIntent>();
            var constraints = new ArrayList<RecommendationConstraint>();
            Clarification clarification = null;
            for (var node : root.get("requests")) {
                if (!node.isObject() || !node.has("route") || !node.get("route").isString()) throw ChatException.invalidResponse();
                var route = ChatIntent.Route.valueOf(node.get("route").asString());
                boolean reasoned = route == ChatIntent.Route.CLARIFY && (node.has("reason") || node.has("source"));
                requireFields(node, reasoned ? Set.of("route", "reason", "source") : route == ChatIntent.Route.DETAIL ? DETAIL : route == ChatIntent.Route.RATIONALE
                        ? REFERENCE : route.name().startsWith("LIST_") ? LIST : recommendation(route) ? RECOMMENDATION : Set.of("route"));
                if (reasoned) {
                    if (node.get("reason").isNull() || node.get("source").isNull()) {
                        if (!node.get("reason").isNull() || !node.get("source").isNull()) throw ChatException.invalidResponse();
                    } else {
                        var evidence = JSON.createObjectNode(); evidence.set("reason", node.get("reason")); evidence.set("source", node.get("source"));
                        clarification = Clarification.parse(evidence);
                        clarification.validate(current);
                    }
                }
                var normalized = JSON.createObjectNode();
                for (String field : List.of("route", "media", "title", "contentId", "releaseYear", "referenceTurn", "referencePosition", "question"))
                    normalized.putNull(field);
                normalized.put("media", "ANY");
                for (var field : node.properties()) {
                    if (reasoned && (field.getKey().equals("reason") || field.getKey().equals("source"))) continue;
                    if (recommendation(route) && (field.getKey().equals("media") || field.getKey().equals("mediaSource"))) continue;
                    normalized.set(field.getKey(), field.getValue());
                }
                String question = boundary == null ? current : operations.isEmpty()
                        ? current.substring(0, boundary) : current.substring(boundary);
                if (boundary != null) normalized.put("question", question);
                if (recommendation(route)) addMedia(node, question, context, constraints);
                operations.add(ChatIntent.parse(normalized.toString()));
            }
            for (var node : root.get("constraints")) {
                var constraint = RecommendationConstraint.parse(node);
                // One authoritative media slot per recommendation; no competing legacy field.
                if (constraint.kind() == RecommendationConstraint.Kind.MEDIA) throw ChatException.invalidResponse();
                constraints.add(constraint);
            }
            return new ChatPlan(operations, comparison, constraints, clarification);
        } catch (RuntimeException e) { throw ChatException.invalidResponse(); }
    }

    private static void addMedia(JsonNode node, String question, List<AiMessage> context,
            List<RecommendationConstraint> constraints) {
        if (!node.get("media").isString()) throw ChatException.invalidResponse();
        var media = ChatIntent.Media.valueOf(node.get("media").asString());
        var source = node.get("mediaSource");
        if (media == ChatIntent.Media.ANY) {
            if (!source.isNull()) throw ChatException.invalidResponse();
            return;
        }
        if (!source.isString()) throw ChatException.invalidResponse();
        String quote = source.asString();
        var constraint = new RecommendationConstraint(RecommendationConstraint.Kind.MEDIA, media.name(), quote, List.of());
        // Only this operation or earlier USER text can support a carried media condition.
        boolean earlierUser = context.subList(0, context.size() - 1).stream()
                .anyMatch(m -> m.role() == AiMessage.Role.USER && m.text().contains(quote));
        if (!question.contains(quote) && !earlierUser) throw ChatException.invalidResponse();
        constraints.add(constraint);
    }

    private static void requireFields(JsonNode node, Set<String> fields) {
        if (!node.isObject() || node.size() != fields.size() || fields.stream().anyMatch(f -> !node.has(f)))
            throw ChatException.invalidResponse();
    }
}
