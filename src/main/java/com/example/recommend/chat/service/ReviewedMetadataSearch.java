package com.example.recommend.chat.service;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Reviewed discovery hints only. Never facts, hard-condition evidence, or duration data. */
@Service
public class ReviewedMetadataSearch {
    static final String POLICY = "reviewed-metadata-discovery-v1";
    static final String RELEASE = "fa075eb4a7d34cf575eb7013d46bce3ad32cfaed3fcc5f8c3cd0c3d37a648bfb";
    static final String SEARCH_SHA = "438b0d950237932c6054677373c3346c31d92bd623f8790cc90d91a18bdfc889";
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final JdbcTemplate jdbc;
    private final String payload;
    private final List<Work> works;

    record Work(long id, String type, String title, String overview, List<String> keywords, List<String> aliases) { }
    record Hit(Work work, boolean alias) { }

    public ReviewedMetadataSearch(DataSource source) {
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(2); jdbc.setMaxRows(1);
        try (var stream = ReviewedMetadataSearch.class.getResourceAsStream("/catalog/metadata-search-v1.json")) {
            if (stream == null) throw new IllegalStateException();
            byte[] bytes = stream.readNBytes(200001);
            if (bytes.length > 200000 || !sha(bytes).equals(SEARCH_SHA)) throw new IllegalStateException();
            payload = new String(bytes, StandardCharsets.UTF_8);
            var root = JSON.readTree(payload);
            if (!text(root, "policy").equals(POLICY) || !text(root, "release_sha").equals(RELEASE)
                    || root.get("keyword_count").asInt() != 26 || root.get("alias_count").asInt() != 55) throw new IllegalStateException();
            var values = new ArrayList<Work>(); var ids = new HashSet<Long>(); int keywords = 0, aliases = 0;
            for (var work : root.get("works")) {
                long id = work.get("content_id").asLong();
                if (id <= 0 || !ids.add(id)) throw new IllegalStateException();
                var labels = new ArrayList<String>(); var titles = new ArrayList<String>();
                for (var k : work.get("keywords")) { labels.add(text(k, "label")); labels.add(text(k, "label_ko")); keywords++; }
                for (var a : work.get("aliases")) { titles.add(text(a, "title")); aliases++; }
                values.add(new Work(id, text(work, "type"), text(work, "title"), text(work, "overview"), List.copyOf(labels), List.copyOf(titles)));
            }
            if (values.size() != 28 || keywords != 26 || aliases != 55) throw new IllegalStateException();
            works = List.copyOf(values);
        } catch (Exception e) { throw new IllegalStateException("Reviewed search resource integrity failed; details withheld."); }
    }

    List<Hit> search(String question, Collection<String> terms) {
        if (!enabled()) return List.of();
        String query = normalized(question.substring(0, Math.min(question.length(), 2000)));
        var needles = terms.stream().limit(32).filter(Objects::nonNull).map(ReviewedMetadataSearch::normalized)
                .filter(t -> t.length() >= 2 && t.length() <= 100).distinct().toList();
        var hits = new ArrayList<Hit>();
        for (var work : works) {
            boolean alias = work.aliases().stream().anyMatch(a -> contains(query, normalized(a)));
            boolean keyword = work.keywords().stream().anyMatch(label -> needles.stream().anyMatch(t -> contains(normalized(label), t)));
            if (alias || keyword) hits.add(new Hit(work, alias));
        }
        return List.copyOf(hits);
    }

    List<Work> exclusions(Collection<String> titles) {
        if (titles.isEmpty() || !enabled()) return List.of();
        var names = titles.stream().map(ReviewedMetadataSearch::normalized).collect(java.util.stream.Collectors.toSet());
        return works.stream().filter(w -> w.aliases().stream().anyMatch(a -> names.contains(normalized(a)))).toList();
    }

    private boolean enabled() {
        try {
            return jdbc.query("""
                    SELECT event_sha,previous_sha,enabled,release_sha,search_sha,
                           LEFT(payload,200001) AS payload,LEFT(event_body,4097) AS event_body
                    FROM catalog_metadata_search_events ORDER BY event_id DESC LIMIT 1
                    """, (rs, index) -> {
                if (rs.getInt("enabled") != 1 || !RELEASE.equals(rs.getString("release_sha"))
                        || !SEARCH_SHA.equals(rs.getString("search_sha")) || !payload.equals(rs.getString("payload"))) return false;
                String body = rs.getString("event_body");
                if (body == null || body.length() > 4096 || !sha(body.getBytes(StandardCharsets.UTF_8)).equals(rs.getString("event_sha"))) return false;
                var event = JSON.readTree(body);
                var flag = event.get("enabled"); var previous = event.get("previous_sha");
                return event.isObject() && event.size() == 8 && text(event, "policy").equals(POLICY)
                        && flag != null && flag.isIntegralNumber() && flag.asInt() == 1
                        && text(event, "release_sha").equals(RELEASE) && text(event, "search_sha").equals(SEARCH_SHA)
                        && previous != null && Objects.equals(previous.isNull() ? null : previous.asString(), rs.getString("previous_sha"))
                        && text(event, "actor").equals("CODEX_DECLARED_NOT_AUTHENTICATED")
                        && !text(event, "reason").isBlank() && !text(event, "declared_at").isBlank();
            }).stream().findFirst().orElse(false);
        } catch (RuntimeException e) {
            // Missing/withdrawn/invalid optional metadata never disables the existing catalog path.
            return false;
        }
    }

    private static String text(JsonNode node, String key) {
        var value = node.get(key);
        if (value == null || !value.isString()) throw new IllegalArgumentException();
        return value.asString();
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
    private static String normalized(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " "); }
    private static boolean contains(String haystack, String needle) {
        if (needle.length() < 2) return false;
        if (needle.codePoints().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3)) return haystack.contains(needle);
        return Pattern.compile("(?<![\\p{L}\\p{N}])"+Pattern.quote(needle)+"(?![\\p{L}\\p{N}])").matcher(haystack).find();
    }
}
