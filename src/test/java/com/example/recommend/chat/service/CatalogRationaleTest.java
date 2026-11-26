package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CatalogRationaleTest {
    CatalogCandidate work = new CatalogCandidate(127, "오만과 편견", "MOVIE", "drama,romance", "2005-09-16", "편견을 극복하는 두 사람의 사랑.");
    String valid = "{\"evidence\":[{\"field\":\"GENRES\",\"quote\":\"romance\"}],\"interpretation\":\"로맨스를 원할 때 고려할 수 있지만 겨울 배경은 확인되지 않아요.\"}";

    @Test void batchRequiresEveryRequestedIdAndEvidenceFromItsOwnWork() {
        var second = new CatalogCandidate(80, "셔터 아일랜드", "MOVIE", "thriller", "", "섬의 실종 사건");
        String first = "{\"contentId\":127,\"evidence\":[{\"field\":\"GENRES\",\"quote\":\"romance\"}],\"interpretation\":\"로맨스 장르\"}";
        String other = "{\"contentId\":80,\"evidence\":[{\"field\":\"GENRES\",\"quote\":\"thriller\"}],\"interpretation\":\"스릴러 장르\"}";
        String validBatch = "{\"works\":[" + other + "," + first + "]}";
        assertEquals(java.util.Set.of(127L, 80L), CatalogRationale.parseMany(validBatch, List.of(work, second)).keySet());
        for (String invalid : List.of("{\"works\":[" + first + "]}", "{\"works\":[" + first + "," + first + "]}",
                validBatch.replace(":80", ":999"), validBatch.replace(":80", ":80.5"),
                validBatch.replace("\"thriller\"", "\"romance\""), validBatch + " {}",
                validBatch.replace("\"스릴러 장르\"", "\"" + "x".repeat(101) + "\"")))
            assertThrows(ChatException.class, () -> CatalogRationale.parseMany(invalid, List.of(work, second)));
    }

    @Test void acceptsOnlyVerbatimEvidenceWithStrictShapeAndLimits() {
        assertEquals("romance", CatalogRationale.parse(valid, work).evidence().get(0).quote());
        for (String invalid : List.of(valid.replace("romance", "겨울 풍경"), valid.replace("GENRES", "DIRECTOR"),
                valid + " {}", valid.replace("\"interpretation\":", "\"sql\":"),
                valid.replace("\"evidence\":", "\"evidence\":[],\"evidence\":"),
                valid.replace("\"romance\"", "\" \""), valid.replace("\"romance\"", "\"" + "x".repeat(161) + "\""),
                "{\"evidence\":[],\"interpretation\":\" \"}", "null"))
            assertEquals(502, assertThrows(ChatException.class, () -> CatalogRationale.parse(invalid, work)).status());
        assertThrows(ChatException.class, () -> new CatalogRationale(List.of(
                new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, "drama"),
                new CatalogRationale.Evidence(CatalogRationale.Field.GENRES, "romance")), "해석"));
        assertTrue(CatalogRationale.parse("{\"evidence\":[],\"interpretation\":\"근거가 부족해요\"}", work).evidence().isEmpty());
    }
}
