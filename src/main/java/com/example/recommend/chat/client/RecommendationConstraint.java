package com.example.recommend.chat.client;

import com.example.recommend.chat.service.ChatException;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** A bounded semantic extraction, never executable SQL or a provider-authored catalog fact. */
public record RecommendationConstraint(Kind kind, String value, String source, List<String> terms) {
    public enum Kind { MEDIA, GENRE_ANY, GENRE_ALL, GENRE_EXCLUDE, YEAR_MIN, YEAR_MAX,
        MINUTES_MIN, MINUTES_MAX, EPISODE_MINUTES_MIN, EPISODE_MINUTES_MAX,
        SETTING, TOPIC, MOOD, SITUATION, AVOID, ORDER, REFERENCE, TITLE_EXCLUDE, UNVERIFIABLE }
    public static final Set<String> GENRES = Set.of("action", "adventure", "animation", "comedy", "crime", "documentary",
            "drama", "family", "fantasy", "history", "horror", "music", "mystery", "romance", "sf", "thriller",
            "war", "western", "kids", "news", "reality", "soap", "talk", "politics", "tv_movie");

    public RecommendationConstraint {
        if (kind == null || value == null || value.isBlank() || value.length() > 80
                || source == null || source.isBlank() || source.length() > 100 || terms == null || terms.size() > 4
                || terms.stream().anyMatch(t -> t == null || t.isBlank() || t.length() < 2 || t.length() > 30)
                || new HashSet<>(terms).size() != terms.size()) throw ChatException.invalidResponse();
        boolean semantic = kind == Kind.SETTING || kind == Kind.TOPIC || kind == Kind.MOOD || kind == Kind.SITUATION || kind == Kind.AVOID;
        if (!semantic && !terms.isEmpty()) throw ChatException.invalidResponse();
        if ((kind == Kind.SETTING || kind == Kind.TOPIC) && terms.isEmpty()) throw ChatException.invalidResponse();
        if (kind.name().startsWith("GENRE_") && !GENRES.contains(value)) throw ChatException.invalidResponse();
        if (kind == Kind.MEDIA && !Set.of("MOVIE", "TV").contains(value)) throw ChatException.invalidResponse();
        if (kind == Kind.ORDER && !Set.of("SHORTER_MOVIE", "SHORTER_EPISODE", "NEWER").contains(value)) throw ChatException.invalidResponse();
        if (kind == Kind.REFERENCE && !Set.of("ONLY_LAST", "EXCLUDE_RECENT").contains(value)) throw ChatException.invalidResponse();
        if (kind.name().startsWith("YEAR_") || kind.name().contains("MINUTES_")) {
            if (!value.matches("[1-9][0-9]{0,3}")) throw ChatException.invalidResponse();
            int number = Integer.parseInt(value);
            if (kind.name().startsWith("YEAR_") ? number < 1800 || number > 2200 : number > 1440)
                throw ChatException.invalidResponse();
        }
        if (kind == Kind.TITLE_EXCLUDE && !source.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)))
            throw ChatException.invalidResponse();
        terms = List.copyOf(terms);
    }

    /** Match a literal topic at a word start, retaining Korean particles but excluding 조리 inside 모조리. */
    public static String topicPattern(String term) {
        return "(^|[^\\p{L}\\p{N}_])(" + java.util.regex.Pattern.quote(term.toLowerCase(Locale.ROOT)) + ")";
    }

    public static RecommendationConstraint parse(JsonNode node) {
        if (!node.isObject() || node.size() != 4 || !node.has("kind") || !node.get("kind").isString()
                || !node.has("value") || !node.get("value").isString() || !node.has("source") || !node.get("source").isString()
                || !node.has("terms") || !node.get("terms").isArray()) throw ChatException.invalidResponse();
        var terms = new ArrayList<String>();
        for (var term : node.get("terms")) {
            if (!term.isString()) throw ChatException.invalidResponse();
            terms.add(term.asString());
        }
        return new RecommendationConstraint(Kind.valueOf(node.get("kind").asString()), node.get("value").asString(),
                node.get("source").asString(), terms);
    }
}
