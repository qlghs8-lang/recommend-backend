package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** Observations only: expected semantic state never enters a provider prompt or router plan. */
final class QualityConversationTrace {
    static List<JsonNode> stories() throws Exception {
        return QualityBaselineTest.JSON.readTree(java.nio.file.Files.readAllBytes(QualityBaselineTest.DEV))
                .get("cases").valueStream().filter(c -> QualityConversationPlan.CASE_IDS.contains(c.get("id").asString())).toList();
    }
    static List<AiMessage> assistants(List<AiMessage> context) {
        return context.stream().filter(m -> m.role() == AiMessage.Role.ASSISTANT).toList();
    }
    static List<Long> lastIds(List<AiMessage> context) {
        return assistants(context).stream().reduce((a,b) -> b).map(AiMessage::contentIds).orElse(List.of());
    }
    static Map<String, Object> reference(JsonNode expected, List<AiMessage> context, Set<Long> carriedExclusions) {
        String rule = expected.get("reference").asString();
        var last = lastIds(context);
        var targets = new LinkedHashSet<Long>();
        String availability = "NOT_REQUIRED";
        switch (rule) {
            case "ONLY_PREVIOUS_PUBLIC_IDS", "LAST_PUBLIC_RECOMMENDATIONS" -> {
                targets.addAll(last); availability = last.isEmpty() ? "MISSING_PREVIOUS_PUBLIC_IDS" : "AVAILABLE";
            }
            case "EXCLUDE_POSITION_2_IF_PUBLIC_ELSE_CLARIFY" -> {
                if (last.size() < 2) availability = "MISSING_PREVIOUS_POSITION_2";
                else { targets.add(last.get(1)); availability = "AVAILABLE"; }
            }
            case "EXCLUDE_PREVIOUS_PUBLIC_IDS" -> {
                assistants(context).forEach(m -> targets.addAll(m.contentIds()));
                availability = targets.isEmpty() ? "MISSING_PREVIOUS_PUBLIC_IDS" : "AVAILABLE";
            }
            case "KEEP_EXCLUSIONS" -> {
                targets.addAll(carriedExclusions); availability = targets.isEmpty() ? "NO_PRIOR_EXCLUSIONS" : "AVAILABLE";
            }
            default -> { }
        }
        return Map.of("rule", rule, "availability", availability, "targetIds", List.copyOf(targets),
                "lastAssistantPublicIds", last, "trustedSource", "SERVER_LOADED_AI_MESSAGE_CONTENT_IDS");
    }
    @SuppressWarnings("unchecked")
    static void carry(Map<String, Object> reference, Set<Long> carried) {
        if (((String)reference.get("rule")).startsWith("EXCLUDE_"))
            carried.addAll((List<Long>)reference.get("targetIds"));
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> facts(JsonNode expected, Map<String, Object> reference, List<Long> ids, Map<Long, JsonNode> works) {
        var violations = new ArrayList<String>();
        var hard = expected.get("hard");
        var targets = (List<Long>)reference.get("targetIds");
        String rule = (String)reference.get("rule");
        for (long id : ids) {
            var work = works.get(id);
            if (work == null) { violations.add(id + ":UNKNOWN_ID"); continue; }
            if (hard.has("media") && !hard.get("media").asString().equals(work.get("type").asString())) violations.add(id+":MEDIA");
            for (var bound : Map.of("movie_minutes_max", "runtime_minutes", "episode_minutes_max", "episode_runtime_minutes").entrySet())
                if (hard.has(bound.getKey())) {
                    var value = work.get(bound.getValue());
                    if (value == null || value.isNull() || value.asInt() < 1 || value.asInt() > hard.get(bound.getKey()).asInt())
                        violations.add(id+":"+bound.getKey());
                }
            if (hard.has("genre_exclude")) {
                Set<String> genres = new HashSet<>(Arrays.asList(work.get("genres").isNull() ? new String[0] : work.get("genres").asString().split(",")));
                for (var genre : hard.get("genre_exclude")) if (genres.contains(genre.asString())) violations.add(id+":GENRE_EXCLUDE");
            }
            if (rule.equals("ONLY_PREVIOUS_PUBLIC_IDS") || rule.equals("LAST_PUBLIC_RECOMMENDATIONS")) {
                if (!targets.contains(id)) violations.add(id+":OUTSIDE_TRUSTED_REFERENCE");
            } else if (rule.startsWith("EXCLUDE_") || rule.equals("KEEP_EXCLUSIONS")) {
                if (targets.contains(id)) violations.add(id+":EXCLUDED_REFERENCE");
                if (reference.get("availability").equals("MISSING_PREVIOUS_POSITION_2")) violations.add(id+":UNRESOLVED_POSITION");
            }
        }
        return Map.of("selectedIds", ids, "violations", violations,
                "state", ids.isEmpty() ? "NO_SELECTION_NOT_A_PASS" : violations.isEmpty() ? "CHECKED_HARD_FACTS_MATCH" : "HARD_FACT_MISMATCH",
                "semanticQuality", "UNJUDGED_REQUIRES_INDEPENDENT_HUMAN_LABELS");
    }
    static void publicOnly(String request) {
        for (String marker : List.of("conversation-owner@example.invalid", "conversation-other@example.invalid", "conversation-unused-password"))
            if (request.contains(marker)) throw new IllegalStateException("Synthetic account data reached a provider request");
    }
}
