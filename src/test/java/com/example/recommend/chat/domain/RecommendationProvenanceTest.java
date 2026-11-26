package com.example.recommend.chat.domain;

import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.service.ChatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationProvenanceTest {
    private RecommendationProvenance sample() {
        return new RecommendationProvenance("내 취향에 맞는 영화 추천", true,
                List.of(new RecommendationProvenance.Basis(7, 3, List.of("sf")),
                        new RecommendationProvenance.Basis(9, 0, List.of())));
    }
    @Test void roundTripAndOrdinalSelectionPreserveOriginalBasisWithoutAnchorData() {
        var saved = sample(); assertEquals(saved, RecommendationProvenance.decode(saved.encode()));
        var selected = saved.select(List.of(9L));
        assertEquals(saved.request(), selected.request()); assertEquals(0, selected.works().get(0).score());
        assertFalse(saved.toString().contains(saved.request()));
        assertThrows(ChatException.class, () -> saved.select(List.of(99L)));
    }
    @Test void publicRecommendationAndLegacyNullRemainDistinct() {
        var saved = new RecommendationProvenance("영화 추천", null, List.of(new RecommendationProvenance.Basis(7, null, List.of())));
        assertEquals(saved, RecommendationProvenance.decode(saved.encode()));
        assertNull(new ChatMessage(null, "legacy", List.of(7L)).provenance());
    }
    @Test void publicJsonDoesNotExposeInternalMetadata() {
        var saved = sample(); var mapper = JsonMapper.builder().build();
        String json = mapper.writeValueAsString(new ChatResponse("공개 답변", new ChatResponse.Usage(1, 1, 2), true, saved.ids(), saved));
        var tree = mapper.readTree(json);
        assertEquals(3, tree.size()); assertFalse(json.contains("positiveHistory")); assertFalse(json.contains("provenance"));
        assertFalse(json.contains(saved.request()));
    }
    @Test void rejectsMismatchedIdsAndMutableOrExcessiveMetadata() {
        var saved = sample();
        assertThrows(IllegalArgumentException.class, () -> new ChatResponse("답변", new ChatResponse.Usage(0, 0, 0), true, List.of(7L), saved));
        assertThrows(ChatException.class, () -> new ChatMessage(null, "답변", List.of(7L), saved));
        assertEquals(8000, new RecommendationProvenance("x".repeat(8000), true, saved.works()).request().length());
        assertThrows(ChatException.class, () -> new RecommendationProvenance("x".repeat(8001), true, saved.works()));
        assertThrows(ChatException.class, () -> new RecommendationProvenance("추천", null, saved.works()));
        assertThrows(ChatException.class, () -> new RecommendationProvenance("추천", true, List.of(saved.works().get(0), saved.works().get(0))));
        assertThrows(UnsupportedOperationException.class, () -> saved.works().clear());
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "null", "[]",
            "{\"version\":2,\"request\":\"x\",\"positiveHistory\":true,\"works\":[]}",
            "{\"version\":1,\"version\":1,\"request\":\"x\",\"positiveHistory\":true,\"works\":[]}",
            "{\"version\":1,\"request\":\"x\",\"positiveHistory\":true,\"works\":[{\"contentId\":1,\"score\":3.5,\"positiveGenres\":[]}]}",
            "{\"version\":1,\"request\":\"x\",\"positiveHistory\":true,\"works\":[{\"contentId\":1,\"score\":3,\"positiveGenres\":[],\"extra\":1}]}"})
    void malformedMetadataFailsClosed(String json) { assertThrows(ChatException.class, () -> RecommendationProvenance.decode(json)); }
}
