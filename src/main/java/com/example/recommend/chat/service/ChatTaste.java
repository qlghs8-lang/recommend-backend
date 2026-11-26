package com.example.recommend.chat.service;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded, request-local preferences. Never part of a provider DTO. */
record ChatTaste(Map<String, Integer> weights) {
    private static final Pattern SNAPSHOT = Pattern.compile(
            "(?m)^(?:개인화 기준|추천 이유)\\(답변 시점\\): [^\\r\\n]*(?:\\r?\\n|$)");

    ChatTaste { weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights)); }

    static String searchText(String question) {
        // Remove only personalization scaffolding; retain title/genre/type terms for lexical filtering.
        return question.replaceAll("(?i)for\\s+me|my\\s+taste", " ")
                .replaceAll("(?<![\\p{L}\\p{N}])(?:내가|나의|나에게|나한테|내|취향(?:으로|에|을|은|는)?|개인화|맞춤(?:형)?|맞는|맞게|좋아요[를을]?|싫어요[를을]?|찜(?:한|을)?|북마크(?:한|를)?|누른|누르[는고]|장르(?:로|를|에|의)?|기반(?:으로|의)?|바탕(?:으로)?|반영(?:해서|한)?)(?![\\p{L}\\p{N}])", " ")
                .replaceAll("(?i)\\b(likes?|dislikes?|bookmarks?|based|on)\\b", " ");
    }

    static Set<String> genres(String csv) {
        var result = new LinkedHashSet<String>();
        if (csv != null) for (String value : csv.split(",")) {
            String genre = CatalogRetriever.canonicalGenre(value);
            if (!genre.isBlank()) result.add(genre);
        }
        return result;
    }

    String summary() {
        return summary(weights.values().stream().anyMatch(w -> w > 0));
    }

    static String summary(boolean positiveHistory) {
        return "개인화 기준(답변 시점): " + (positiveHistory
                ? "최근 좋아요·찜·싫어요의 장르 점수를 반영했어요."
                : "긍정적인 장르 선호 기록이 부족해요. 요청 조건과 평점도 함께 사용했어요.")
                + " 이미 좋아요·찜·싫어요한 작품은 제외했어요.";
    }

    String reason(String csv) {
        var genres = genres(csv);
        var positive = genres.stream().filter(g -> weights.getOrDefault(g, 0) > 0).toList();
        int score = genres.stream().mapToInt(g -> weights.getOrDefault(g, 0)).sum();
        return reason(score, positive);
    }

    static String reason(int score, List<String> positive) {
        return "추천 이유(답변 시점): " + (score > 0
                ? "좋아요·찜에서 얻은 장르 선호와 일치: " + String.join(", ", positive)
                    + " (싫어요 감점 포함 장르 점수 " + score + ")."
                : "긍정적인 장르 점수 일치가 없어 요청 조건과 카탈로그 평점·조회수를 기준으로 골랐어요.");
    }

    com.example.recommend.chat.domain.RecommendationProvenance.Basis basis(long id, String csv) {
        var genres = genres(csv);
        return new com.example.recommend.chat.domain.RecommendationProvenance.Basis(id,
                genres.stream().mapToInt(g -> weights.getOrDefault(g, 0)).sum(),
                genres.stream().filter(g -> weights.getOrDefault(g, 0) > 0).toList());
    }

    static String withoutSnapshots(String answer) { return SNAPSHOT.matcher(answer).replaceAll(""); }
}
