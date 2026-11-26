package com.example.recommend.chat.domain;

import com.example.recommend.chat.service.ChatException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Server-only, bounded recommendation-time basis. Never put this in an AiMessage or public DTO. */
public record RecommendationProvenance(String request, Boolean positiveHistory, List<Basis> works) {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public record Basis(long contentId, Integer score, List<String> positiveGenres) {
        public Basis {
            if (contentId <= 0 || score != null && Math.abs((long) score) > 10000 || positiveGenres == null
                    || positiveGenres.size() > 16 || positiveGenres.stream().anyMatch(g -> g == null || g.isBlank()
                    || g.length() > 200 || g.chars().anyMatch(Character::isISOControl))
                    || new HashSet<>(positiveGenres).size() != positiveGenres.size()) throw ChatException.invalidResponse();
            positiveGenres = List.copyOf(positiveGenres);
        }
    }
    public RecommendationProvenance {
        if (request == null || request.isBlank() || request.length() > 8000 || works == null || works.isEmpty()
                || works.size() > 3 || works.stream().anyMatch(Objects::isNull)
                || works.stream().map(Basis::contentId).distinct().count() != works.size()) throw ChatException.invalidResponse();
        for (var work : works) if ((positiveHistory == null) != (work.score() == null)
                || positiveHistory == null && !work.positiveGenres().isEmpty()) throw ChatException.invalidResponse();
        works = List.copyOf(works);
    }
    public List<Long> ids() { return works.stream().map(Basis::contentId).toList(); }
    public RecommendationProvenance select(List<Long> ids) {
        if (ids == null || ids.isEmpty() || !ids().containsAll(ids)) throw ChatException.invalidResponse();
        return new RecommendationProvenance(request, positiveHistory,
                ids.stream().map(id -> works.stream().filter(w -> w.contentId() == id).findFirst().orElseThrow()).toList());
    }
    public String encode() {
        var root = new LinkedHashMap<String, Object>(); root.put("version", 1); root.put("request", request);
        root.put("positiveHistory", positiveHistory); root.put("works", works);
        String encoded = JSON.writeValueAsString(root);
        if (encoded.length() > 24000) throw ChatException.invalidResponse();
        return encoded;
    }
    public static RecommendationProvenance decode(String encoded) {
        try {
            if (encoded == null || encoded.length() > 24000) throw ChatException.invalidResponse();
            var root = JSON.readTree(encoded);
            if (!root.isObject() || root.size() != 4 || !root.has("version") || !root.get("version").isIntegralNumber()
                    || !root.get("version").canConvertToInt() || root.get("version").asInt() != 1 || !root.has("request") || !root.get("request").isString()
                    || !root.has("positiveHistory") || !(root.get("positiveHistory").isNull() || root.get("positiveHistory").isBoolean())
                    || !root.has("works") || !root.get("works").isArray()) throw ChatException.invalidResponse();
            var works = new ArrayList<Basis>();
            for (var row : root.get("works")) {
                if (!row.isObject() || row.size() != 3 || !row.has("contentId") || !row.get("contentId").isIntegralNumber()
                        || !row.get("contentId").canConvertToLong() || !row.has("score")
                        || !(row.get("score").isNull() || row.get("score").isIntegralNumber() && row.get("score").canConvertToInt())
                        || !row.has("positiveGenres") || !row.get("positiveGenres").isArray()) throw ChatException.invalidResponse();
                var genres = new ArrayList<String>();
                for (var genre : row.get("positiveGenres")) { if (!genre.isString()) throw ChatException.invalidResponse(); genres.add(genre.asString()); }
                works.add(new Basis(row.get("contentId").asLong(), row.get("score").isNull() ? null : row.get("score").asInt(), genres));
            }
            return new RecommendationProvenance(root.get("request").asString(),
                    root.get("positiveHistory").isNull() ? null : root.get("positiveHistory").asBoolean(), works);
        } catch (RuntimeException e) { throw ChatException.invalidResponse(); }
    }
    @Override public String toString() { return "RecommendationProvenance[server-only]"; }
}
