package com.example.recommend.chat.client;

import java.util.List;

/** Request-scoped catalog and approved own-reaction reads. No caller-selected identity or SQL. */
public interface CatalogTools {
    String execute(String name, String arguments);
    List<CatalogCandidate> candidates();
    default boolean privateDataRead() { return false; }
    default List<RecommendationConstraint> constraints() { return List.of(); }
    default java.util.Map<String, List<Long>> referenceScope() { return java.util.Map.of(); }
}
