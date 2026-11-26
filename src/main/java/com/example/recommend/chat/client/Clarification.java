package com.example.recommend.chat.client;

import com.example.recommend.chat.service.ChatException;
import tools.jackson.databind.JsonNode;

/** A bounded reason with verbatim current-user evidence, never a model-authored reply. */
public record Clarification(Reason reason, String source) {
    public enum Reason { TV_DURATION_UNIT, TITLE_BOUNDARY, QUALITATIVE_COMPARISON }

    public Clarification {
        if (reason == null || source == null || source.isBlank() || source.length() > 100
                || !source.equals(source.strip())) throw ChatException.invalidResponse();
    }

    public void validate(String current) {
        if (current == null || !current.contains(source)) throw ChatException.invalidResponse();
    }

    public static Clarification parse(JsonNode node) {
        try {
            if (node == null || !node.isObject() || node.size() != 2 || !node.has("reason") || !node.has("source")
                    || !node.get("reason").isString() || !node.get("source").isString()) throw ChatException.invalidResponse();
            return new Clarification(Reason.valueOf(node.get("reason").asString()), node.get("source").asString());
        } catch (RuntimeException e) { throw ChatException.invalidResponse(); }
    }
}
