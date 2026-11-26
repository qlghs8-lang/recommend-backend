package com.example.recommend.chat.client;

import com.example.recommend.chat.service.ChatException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Verbatim public evidence is validated; interpretation remains explicitly labelled model inference. */
public record CatalogRationale(List<Evidence> evidence, String interpretation) {
    public enum Field { TITLE, GENRES, OVERVIEW }
    public record Evidence(Field field, String quote) { }
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public CatalogRationale {
        if (evidence == null || evidence.size() > 2 || interpretation == null
                || interpretation.isBlank() || interpretation.length() > 400) throw ChatException.invalidResponse();
        var fields = EnumSet.noneOf(Field.class);
        for (var item : evidence) if (item == null || item.field() == null || item.quote() == null
                || item.quote().isBlank() || item.quote().length() > 160 || !fields.add(item.field()))
            throw ChatException.invalidResponse();
        evidence = List.copyOf(evidence);
    }

    public void validate(CatalogCandidate work) {
        for (var item : evidence) {
            String source = switch (item.field()) {
                case TITLE -> work.title(); case GENRES -> work.genres(); case OVERVIEW -> work.overview();
            };
            if (source == null || !source.contains(item.quote())) throw ChatException.invalidResponse();
        }
    }

    public static CatalogRationale parse(String text, CatalogCandidate work) {
        try {
            if (text == null || text.length() > 8000) throw ChatException.invalidResponse();
            var root = JSON.readTree(text);
            if (!root.isObject() || root.size() != 2 || !root.has("evidence") || !root.get("evidence").isArray()
                    || !root.has("interpretation") || !root.get("interpretation").isString()) throw ChatException.invalidResponse();
            var evidence = new ArrayList<Evidence>();
            for (var node : root.get("evidence")) {
                if (!node.isObject() || node.size() != 2 || !node.has("field") || !node.get("field").isString()
                        || !node.has("quote") || !node.get("quote").isString()) throw ChatException.invalidResponse();
                evidence.add(new Evidence(Field.valueOf(node.get("field").asString()), node.get("quote").asString()));
            }
            var result = new CatalogRationale(evidence, root.get("interpretation").asString());
            result.validate(work);
            return result;
        } catch (RuntimeException e) { throw ChatException.invalidResponse(); }
    }

    public static Map<Long, CatalogRationale> parseMany(String text, List<CatalogCandidate> works) {
        try {
            if (text == null || text.length() > 16000 || works.isEmpty() || works.size() > 5)
                throw ChatException.invalidResponse();
            var allowed = new HashMap<Long, CatalogCandidate>();
            for (var work : works) if (allowed.put(work.id(), work) != null) throw ChatException.invalidResponse();
            var root = JSON.readTree(text);
            if (!root.isObject() || root.size() != 1 || !root.has("works") || !root.get("works").isArray()
                    || root.get("works").size() != works.size()) throw ChatException.invalidResponse();
            var result = new LinkedHashMap<Long, CatalogRationale>();
            for (var node : root.get("works")) {
                if (!node.isObject() || node.size() != 3 || !node.has("contentId")
                        || !node.get("contentId").isIntegralNumber() || !node.get("contentId").canConvertToLong()
                        || !node.has("evidence") || !node.has("interpretation")) throw ChatException.invalidResponse();
                long id = node.get("contentId").asLong();
                if (!allowed.containsKey(id) || result.containsKey(id)) throw ChatException.invalidResponse();
                var rationale = parse(JSON.writeValueAsString(Map.of("evidence", node.get("evidence"),
                        "interpretation", node.get("interpretation"))), allowed.get(id));
                if (rationale.evidence().size() > 1 || rationale.interpretation().length() > 100
                        || rationale.evidence().stream().anyMatch(e -> e.quote().length() > 40)) throw ChatException.invalidResponse();
                result.put(id, rationale);
            }
            return Collections.unmodifiableMap(result);
        } catch (RuntimeException e) { throw ChatException.invalidResponse(); }
    }
}
