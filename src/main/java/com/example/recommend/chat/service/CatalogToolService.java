package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CatalogToolService {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final CatalogRetriever retriever;
    private final ChatInteractionReader interactions;

    public CatalogTools session(long userId, List<CatalogCandidate> initial) {
        return session(userId, initial, RecommendationCriteria.NONE);
    }

    public CatalogTools session(long userId, List<CatalogCandidate> initial, RecommendationCriteria criteria) {
        if (userId <= 0 || initial.size() > CatalogRetriever.MAX_CANDIDATES) throw new IllegalArgumentException();
        return new CatalogTools() {
            private final Map<Long, CatalogCandidate> seen = new LinkedHashMap<>();
            private final Set<Long> reactionIds = new HashSet<>();
            private int calls;
            private boolean privateDataRead;
            { initial.stream().filter(criteria::matches).forEach(c -> seen.put(c.id(), c)); }

            @Override public List<CatalogCandidate> candidates() { return List.copyOf(seen.values()); }
            @Override public boolean privateDataRead() { return privateDataRead; }
            @Override public List<RecommendationConstraint> constraints() { return criteria.constraints(); }
            @Override public Map<String, List<Long>> referenceScope() {
                return criteria.onlyIds().isEmpty() && criteria.excludedIds().isEmpty() ? Map.of()
                        : Map.of("onlyContentIds", criteria.onlyIds().stream().sorted().toList(),
                                "excludedContentIds", criteria.excludedIds().stream().sorted().toList());
            }

            @Override public String execute(String name, String arguments) {
                if (++calls > 3 || arguments == null || arguments.length() > 2048) throw ChatException.invalidResponse();
                try {
                    var args = JSON.readTree(arguments);
                    if (!args.isObject() || args.size() != 1) throw ChatException.invalidResponse();
                    Object result;
                    switch (name) {
                        case "search_catalog" -> {
                            var query = args.get("query");
                            if (query == null || !query.isString() || query.asString().isBlank()
                                    || query.asString().length() > 200) throw ChatException.invalidResponse();
                            var context = List.of(new AiMessage(AiMessage.Role.USER, query.asString()));
                            // Refinement may narrow the query but never replace the original hard constraints.
                            var found = (criteria.active() ? retriever.refine(context, criteria) : retriever.retrieve(context))
                                    .stream().filter(criteria::matches).toList();
                            found.forEach(c -> seen.put(c.id(), c));
                            result = found;
                        }
                        case "get_content" -> {
                            var value = args.get("contentId");
                            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0)
                                throw ChatException.invalidResponse();
                            var found = retriever.detail(value.asLong()).filter(criteria::matches);
                            found.ifPresent(c -> seen.put(c.id(), c));
                            result = found.<Object>map(c -> Map.of("content", c)).orElse(Map.of("found", false));
                        }
                        case "get_my_interactions" -> {
                            var values = args.get("contentIds");
                            if (values == null || !values.isArray() || values.isEmpty() || values.size() > 12)
                                throw ChatException.invalidResponse();
                            var ids = new LinkedHashSet<Long>();
                            for (var value : values) {
                                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0
                                        || !seen.containsKey(value.asLong()) || !ids.add(value.asLong()))
                                    throw ChatException.invalidResponse();
                            }
                            var combined = new HashSet<>(reactionIds);
                            combined.addAll(ids);
                            if (combined.size() > 12) throw ChatException.invalidResponse();
                            // userId is captured from server authentication; it is never a tool argument/output.
                            result = interactions.read(userId, ids);
                            privateDataRead = true;
                            reactionIds.addAll(ids);
                        }
                        default -> throw ChatException.invalidResponse();
                    }
                    return JSON.writeValueAsString(result);
                } catch (ChatException e) { throw e; }
                catch (RuntimeException e) { throw ChatException.invalidResponse(); }
            }
        };
    }

}
