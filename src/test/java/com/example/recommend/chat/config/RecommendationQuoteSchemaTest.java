package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.openai.models.responses.ResponseTextConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline schema/evidence regressions, not a provider-acceptance or semantic-quality score. */
class RecommendationQuoteSchemaTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    static JsonNode schema(List<CatalogCandidate> works, List<RecommendationConstraint> constraints) throws Exception {
        var method = OpenAiClient.class.getDeclaredMethod("recommendationFormat", List.class, List.class);
        method.setAccessible(true);
        var config = (ResponseTextConfig) method.invoke(null, works, constraints);
        var values = new LinkedHashMap<String, Object>();
        config.format().orElseThrow().jsonSchema().orElseThrow().schema()._additionalProperties()
                .forEach((key, value) -> values.put(key, value.convert(Object.class)));
        return JSON.readTree(JSON.writeValueAsString(values));
    }
    static CatalogCandidate work(String title, String genres, String overview) {
        return new CatalogCandidate(404, title, "TV", genres, "", overview);
    }
    static RecommendationConstraint topic(String term) {
        return new RecommendationConstraint(RecommendationConstraint.Kind.TOPIC, term, term, List.of(term));
    }
    static List<String> quotes(JsonNode schema, CatalogRationale.Field field) {
        var found = new ArrayList<String>();
        var items = schema.get("properties").get("recommendations").get("items").get("anyOf");
        if (items == null) return found;
        for (var entry : items) for (var variant : entry.get("properties").get("evidence").get("items").get("anyOf")) {
            var properties = variant.get("properties");
            if (field.name().equals(properties.get("field").get("enum").get(0).asString()))
                properties.get("quote").get("enum").forEach(value -> found.add(value.asString()));
        }
        return found;
    }
    static void assertSafeVerbatim(JsonNode schema, CatalogCandidate work) {
        for (var field : CatalogRationale.Field.values()) for (var quote : quotes(schema, field)) {
            assertTrue(quote.length() <= 40 && !quote.isBlank());
            assertFalse(quote.codePoints().anyMatch(cp -> cp == '"' || cp == '\\' || Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029
                    || cp >= 0xd800 && cp <= 0xdfff));
            new CatalogRationale(List.of(new CatalogRationale.Evidence(field, quote)), "원문 근거").validate(work);
        }
    }

    @Test void frozenD10QuoteKeepsItsTopicAndExactOriginalTextWithoutLineFeeds() throws Exception {
        var frozen = JSON.readTree(Files.readString(Path.of("src/test/resources/chat/schema-d10-original-v1.json")));
        String original = frozen.get("properties").get("recommendations").get("items").get("anyOf").get(10)
                .get("properties").get("evidence").get("items").get("anyOf").get(0)
                .get("properties").get("quote").get("enum").get(0).asString();
        assertEquals(2, original.chars().filter(ch -> ch == '\n').count());
        var work = work("나루토", "animation", original);
        var actual = schema(List.of(work), List.of(topic("동료")));
        assertEquals(List.of(original.substring(0, original.indexOf('\n')).strip()), quotes(actual, CatalogRationale.Field.OVERVIEW));
        assertSafeVerbatim(actual, work);
    }

    @Test void frozenD09QuoteKeepsTheWorkAndCompleteTopicWithoutReplacingTheDoubleQuote() throws Exception {
        var frozen = JSON.readTree(Files.readString(Path.of("src/test/resources/chat/schema-d09-original-v1.json")));
        String original = frozen.get("properties").get("recommendations").get("items").get("anyOf").get(5)
                .get("properties").get("evidence").get("items").get("anyOf").get(0)
                .get("properties").get("quote").get("enum").get(0).asString();
        assertEquals(1, original.chars().filter(ch -> ch == '"').count());
        var work = new CatalogCandidate(434, "스파이 패밀리", "TV", "animation", "", original);
        var actual = schema(List.of(work), List.of(topic("가족")));
        assertEquals(List.of(original.substring(0, original.length() - 1)), quotes(actual, CatalogRationale.Field.OVERVIEW));
        assertEquals(434, actual.get("properties").get("recommendations").get("items").get("anyOf").get(0)
                .get("properties").get("contentId").get("enum").get(0).asInt());
        assertSafeVerbatim(actual, work);
    }

    @Test void allPublicFieldsSplitControlsAndOverviewRetainsTextAfterTheBoundary() throws Exception {
        for (String separator : List.of("\n", "\r\n", "\t", "\0", "\u000b", "\u007f", "\u0085", "\u2028", "\u2029", "\"", "\\")) {
            var work = work(separator + "공개" + separator + "제목", separator + "드라마" + separator,
                    separator + "앞 이야기" + separator + "뒤 이야기");
            var actual = schema(List.of(work), List.of());
            assertEquals(List.of("공개"), quotes(actual, CatalogRationale.Field.TITLE));
            assertEquals(List.of("앞 이야기", "뒤 이야기"), quotes(actual, CatalogRationale.Field.OVERVIEW));
            assertSafeVerbatim(actual, work);
        }
    }

    @Test void topicQuotesRetainCompleteCuesAcrossDifferentLinesAndFields() throws Exception {
        var work = work("공개\n우주 탐험", "science fiction", "줄바꿈\r\n우주🚀 탐험\t가족의 재회");
        var actual = schema(List.of(work), List.of(topic("우주"), topic("가족")));
        assertEquals(List.of("우주 탐험"), quotes(actual, CatalogRationale.Field.TITLE));
        assertEquals(List.of("우주🚀 탐험", "가족의 재회"), quotes(actual, CatalogRationale.Field.OVERVIEW));
        assertTrue(quotes(actual, CatalogRationale.Field.GENRES).isEmpty());
        assertSafeVerbatim(actual, work);
    }

    @Test void validSurrogatesStayPairedAndMalformedSurrogatesBecomeBoundaries() throws Exception {
        for (String overview : List.of("가".repeat(39) + "🚀뒤", "앞\ud800뒤\udc00끝", "가".repeat(399) + "🚀")) {
            var work = work(overview, "드라마", overview);
            var actual = schema(List.of(work), List.of());
            assertSafeVerbatim(actual, work);
        }
        var work = work("제목", "드라마", "가".repeat(39) + "🚀뒤");
        assertEquals(List.of("가".repeat(39), "🚀뒤"), quotes(schema(List.of(work), List.of()), CatalogRationale.Field.OVERVIEW));
    }

    @Test void apostrophesAndOtherOrdinaryPunctuationRemainVerbatim() throws Exception {
        var work = work("제목 'A'", "드라마/모험", "원문 경로와 '문장', 그리고 🚀 이야기");
        var actual = schema(List.of(work), List.of());
        assertEquals(List.of(work.title()), quotes(actual, CatalogRationale.Field.TITLE));
        assertEquals(List.of(work.overview()), quotes(actual, CatalogRationale.Field.OVERVIEW));
        assertSafeVerbatim(actual, work);
    }

    @Test void quotedAndBackslashSeparatedTopicsKeepCuesOnEitherSideWithoutJoiningText() throws Exception {
        for (String separator : List.of("\"", "\\", "\"\\\n\"")) {
            var work = work(separator + "가족" + separator, "drama", "가족" + separator + "성장 🚀 이야기" + separator);
            var actual = schema(List.of(work), List.of(topic("가족"), topic("성장")));
            assertEquals(List.of("가족"), quotes(actual, CatalogRationale.Field.TITLE));
            assertEquals(List.of("가족", "성장 🚀 이야기"), quotes(actual, CatalogRationale.Field.OVERVIEW));
            assertSafeVerbatim(actual, work);
            assertThrows(com.example.recommend.chat.service.ChatException.class, () -> new CatalogRationale(List.of(
                    new CatalogRationale.Evidence(CatalogRationale.Field.OVERVIEW, "가족성장 🚀 이야기")), "해석").validate(work));
        }
    }

    @Test void splittingInsideATopicNeverCreatesAnIncompleteCueAlternative() throws Exception {
        var work = work("\"가족 모험\"", "drama", "가족\"모험 다음에 가족 모험을 떠난다.");
        var actual = schema(List.of(work), List.of(topic("가족 모험")));
        assertEquals(List.of("가족 모험"), quotes(actual, CatalogRationale.Field.TITLE));
        assertEquals(List.of("가족 모험을 떠난다."), quotes(actual, CatalogRationale.Field.OVERVIEW));
        assertSafeVerbatim(actual, work);
    }

    @Test void anUnsafeCharacterInsideTheRequiredCueCannotBeSilentlyRemoved() throws Exception {
        var work = work("제목", "drama", "가족\"모험을 함께한다.");
        var actual = schema(List.of(work), List.of(topic("가족\"모험")));
        assertEquals(0, actual.get("properties").get("recommendations").get("maxItems").asInt());
        assertTrue(quotes(actual, CatalogRationale.Field.OVERVIEW).isEmpty());
    }

    @Test void quoteBoundariesDoNotExpandTheFortyOrFourHundredCharacterPrefixes() throws Exception {
        var work = work("가".repeat(39) + "\"숨은 제목", "drama", "가".repeat(398) + "\"나뒤");
        var actual = schema(List.of(work), List.of());
        assertEquals(List.of("가".repeat(39)), quotes(actual, CatalogRationale.Field.TITLE));
        assertTrue(quotes(actual, CatalogRationale.Field.OVERVIEW).contains("나"));
        assertFalse(quotes(actual, CatalogRationale.Field.OVERVIEW).stream().anyMatch(q -> q.contains("뒤")));
        assertSafeVerbatim(actual, work);
    }

    @Test void fragmentedCatalogKeepsExistingChoiceAndEnumBounds() throws Exception {
        var works = new ArrayList<CatalogCandidate>();
        for (int id = 1; id <= 48; id++) works.add(new CatalogCandidate(id, "제목\"다음", "MOVIE", "드라마\\모험", "",
                java.util.stream.IntStream.range(0, 80).mapToObj(i -> "단락" + i + "\"\\\n").collect(java.util.stream.Collectors.joining())));
        var actual = schema(works, List.of());
        for (var field : CatalogRationale.Field.values())
            assertEquals(48 * (field == CatalogRationale.Field.OVERVIEW ? 11 : 1), quotes(actual, field).size());
        assertTrue(enumCount(actual) <= 1000);
    }
    static int enumCount(JsonNode node) {
        int count = node.isObject() && node.has("enum") ? node.get("enum").size() : 0;
        if (node.isContainer()) for (var child : node) count += enumCount(child);
        return count;
    }

    @Test void fieldsWithoutSafeEvidenceCannotCreateARecommendationAlternative() throws Exception {
        var actual = schema(List.of(work("\n\ud800\"", "\t\\", "\r\0\"\\")), List.of());
        assertEquals(0, actual.get("properties").get("recommendations").get("maxItems").asInt());
        assertTrue(quotes(actual, CatalogRationale.Field.OVERVIEW).isEmpty());
    }
}
