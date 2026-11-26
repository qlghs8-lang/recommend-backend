package com.example.recommend.chat.client;

import com.example.recommend.domain.Content;

/** Public catalog facts only; never a JPA entity or user profile in the provider request. */
public record CatalogCandidate(long id, String title, String type, String genres,
                               String releaseDate, String overview, Integer runtimeMinutes, Integer episodeRuntimeMinutes) {
    public CatalogCandidate(long id, String title, String type, String genres, String releaseDate, String overview) {
        this(id, title, type, genres, releaseDate, overview, null, null);
    }
    public static CatalogCandidate from(Content content) {
        return new CatalogCandidate(content.getId(), clip(content.getTitle(), 200),
                clip(content.getType(), 10), clip(content.getGenres(), 200),
                content.getReleaseDate() == null ? "" : content.getReleaseDate().toString(),
                clip(content.getOverview(), 400), validDuration(content.getRuntimeMinutes()), validDuration(content.getEpisodeRuntimeMinutes()));
    }

    private static Integer validDuration(Integer value) { return value == null || value <= 0 || value > 1440 ? null : value; }

    private static String clip(String value, int limit) {
        if (value == null) return "";
        return value.codePointCount(0, value.length()) <= limit ? value
                : value.substring(0, value.offsetByCodePoints(0, limit));
    }
}
