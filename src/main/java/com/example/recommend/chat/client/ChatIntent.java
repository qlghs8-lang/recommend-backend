package com.example.recommend.chat.client;

import com.example.recommend.chat.service.ChatException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Capability and explicit work locator only: never identity, SQL or a model-authored answer. */
public record ChatIntent(Route route, Media media, String title, Long contentId, Integer releaseYear,
        Integer referenceTurn, Integer referencePosition, String question) {
    public enum Route { RECOMMEND, PERSONALIZED, LIST_LIKE, LIST_DISLIKE, LIST_BOOKMARK,
        DETAIL, RATIONALE, EXPLAIN, DENIED, CLARIFY, UNSUPPORTED, OUT_OF_SCOPE, GREETING }
    public enum Media { ANY, MOVIE, TV }
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public ChatIntent(Route route, Media media) { this(route, media, null, null, null); }
    public ChatIntent(Route route, Media media, String title, Long contentId, Integer releaseYear) {
        this(route, media, title, contentId, releaseYear, null, null, null);
    }

    public ChatIntent {
        if (route == null || media == null || (!route.name().startsWith("LIST_") && route != Route.DETAIL && media != Media.ANY))
            throw ChatException.invalidResponse();
        if (question != null && (question.isBlank() || question.length() > 8000)) throw ChatException.invalidResponse();
        if (route == Route.RATIONALE) {
            if (referenceTurn == null || title != null || contentId != null || releaseYear != null)
                throw ChatException.invalidResponse();
        } else if (route == Route.DETAIL) {
            if ((title != null ? 1 : 0) + (contentId != null ? 1 : 0) + (referenceTurn != null ? 1 : 0) != 1
                    || (title != null && (title.isBlank() || title.length() > 200 || !title.equals(title.strip())))
                    || (contentId != null && contentId <= 0)
                    || (releaseYear != null && (releaseYear < 1800 || releaseYear > 2200)))
                throw ChatException.invalidResponse();
        } else if (title != null || contentId != null || releaseYear != null || referenceTurn != null) {
            throw ChatException.invalidResponse();
        }
        if ((referenceTurn == null) != (referencePosition == null)
                || (referenceTurn != null && (referenceTurn < 1 || referenceTurn > 6
                || referencePosition < 0 || referencePosition > 5 || releaseYear != null || media != Media.ANY)))
            throw ChatException.invalidResponse();
    }

    public static ChatIntent parse(String text) {
        try {
            if (text == null || text.length() > 16000) throw ChatException.invalidResponse();
            var root = JSON.readTree(text);
            if (!root.isObject() || root.size() != 8 || !root.has("route") || !root.has("media")
                    || !root.has("title") || !root.has("contentId") || !root.has("releaseYear")
                    || !root.has("referenceTurn") || !root.has("referencePosition") || !root.has("question")
                    || (!root.get("question").isNull() && !root.get("question").isString())
                    || !root.get("route").isString() || !root.get("media").isString())
                throw ChatException.invalidResponse();
            var title = root.get("title"); var id = root.get("contentId"); var year = root.get("releaseYear");
            var turn = root.get("referenceTurn"); var position = root.get("referencePosition");
            if ((!title.isNull() && !title.isString())
                    || (!id.isNull() && (!id.isIntegralNumber() || !id.canConvertToLong()))
                    || (!year.isNull() && (!year.isIntegralNumber() || !year.canConvertToInt()))
                    || (!turn.isNull() && (!turn.isIntegralNumber() || !turn.canConvertToInt()))
                    || (!position.isNull() && (!position.isIntegralNumber() || !position.canConvertToInt())))
                throw ChatException.invalidResponse();
            return new ChatIntent(Route.valueOf(root.get("route").asString()), Media.valueOf(root.get("media").asString()),
                    title.isNull() ? null : title.asString(), id.isNull() ? null : id.asLong(), year.isNull() ? null : year.asInt(),
                    turn.isNull() ? null : turn.asInt(), position.isNull() ? null : position.asInt(),
                    root.get("question").isNull() ? null : root.get("question").asString());
        } catch (RuntimeException e) { throw ChatException.invalidResponse(); }
    }
}
