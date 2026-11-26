package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.config.AiProperties;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.recommend.chat.client.RecommendationConstraint.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationCriteriaTest {
    RecommendationConstraint c(RecommendationConstraint.Kind kind, String value, String source, String... terms) {
        return new RecommendationConstraint(kind, value, source, List.of(terms));
    }
    RecommendationCriteria criteria(RecommendationConstraint... conditions) {
        return new RecommendationCriteria(List.of(conditions), Set.of(), Set.of(), false);
    }
    CatalogCandidate movie(long id, Integer runtime, String genre, String year) {
        return new CatalogCandidate(id, "작품", "MOVIE", genre, year, "희망을 찾아가는 친구들의 이야기", runtime, null);
    }

    @Test void extractedValuesAreAllowlistedBoundedAndCannotBecomeExecutableQueries() {
        for (var kind : List.of(MEDIA, GENRE_ALL, ORDER, REFERENCE, MINUTES_MAX, YEAR_MIN)) {
            assertThrows(ChatException.class, () -> c(kind, "DROP TABLE contents", "요청"));
        }
        assertThrows(ChatException.class, () -> c(MINUTES_MAX, "90.5", "요청"));
        assertThrows(ChatException.class, () -> c(MINUTES_MAX, "0", "요청"));
        assertThrows(ChatException.class, () -> c(MEDIA, "MOVIE", "요청", "불필요"));
        assertThrows(ChatException.class, () -> c(SETTING, "winter", "요청", "눈"));
        assertThrows(ChatException.class, () -> c(TITLE_EXCLUDE, "가짜 제목", "다른 제목 제외"));
    }

    @Test void sourcesMustComeFromUserTextAndReferencesUseOnlyServerMetadata() {
        var context = List.of(new AiMessage(AiMessage.Role.USER, "90분 이하 영화 추천"),
                new AiMessage(AiMessage.Role.ASSISTANT, "120분 작품 (ID: 999)", List.of(1L, 2L)),
                new AiMessage(AiMessage.Role.USER, "그중 더 짧은 걸로"));
        var criteria = RecommendationCriteria.from(List.of(c(MINUTES_MAX, "90", "90분 이하"),
                c(REFERENCE, "ONLY_LAST", "그중"), c(ORDER, "SHORTER_MOVIE", "더 짧은")), context, context.get(2).text());
        assertEquals(Set.of(1L, 2L), criteria.onlyIds());
        assertTrue(criteria.matches(movie(1, 90, "drama", "2020-01-01")));
        assertFalse(criteria.matches(movie(999, 80, "drama", "2020-01-01")));
        assertThrows(ChatException.class, () -> RecommendationCriteria.from(List.of(c(MINUTES_MAX, "120", "120분")), context, context.get(2).text()));
        assertThrows(ChatException.class, () -> RecommendationCriteria.from(List.of(c(MINUTES_MAX, "90", "가짜 원문")), context, context.get(2).text()));
    }

    @Test void hiddenOrAbsentLastReferencesDoNotFallBackToOlderPublicRecommendations() {
        var context = List.of(new AiMessage(AiMessage.Role.USER, "추천"), new AiMessage(AiMessage.Role.ASSISTANT, "공개", List.of(1L)),
                new AiMessage(AiMessage.Role.USER, "내 찜"), new AiMessage(AiMessage.Role.ASSISTANT, "비공개 목록 제거됨"),
                new AiMessage(AiMessage.Role.USER, "그중 더 짧은 것"));
        assertNotNull(RecommendationCriteria.from(List.of(c(REFERENCE, "ONLY_LAST", "그중")), context, context.get(4).text()).clarification());
    }

    @Test void conflictsAndUnverifiableAbsenceAreExplicitRatherThanSilentlyRelaxed() {
        assertNotNull(criteria(c(MINUTES_MIN, "120", "2시간 이상"), c(MINUTES_MAX, "90", "90분 이하")).clarification());
        assertNotNull(criteria(c(GENRE_ALL, "horror", "공포"), c(GENRE_EXCLUDE, "horror", "공포 제외")).clarification());
        assertNotNull(criteria(c(MEDIA, "TV", "시리즈"), c(MINUTES_MAX, "90", "영화 90분")).clarification());
        var avoid = criteria(c(AVOID, "violence", "잔인한 장면은 전혀 없는", "잔인"));
        assertTrue(avoid.clarification().contains("보장할 수 없는 조건"));
    }

    @Test void unknownDurationAndDatesNeverPassHardFiltersAndGenreExclusionIsTokenBased() {
        var criteria = criteria(c(MINUTES_MAX, "120", "두 시간 이내"), c(YEAR_MIN, "2000", "2000년 이후"),
                c(GENRE_EXCLUDE, "horror", "공포 제외"));
        assertTrue(criteria.matches(movie(1, 120, "drama,thriller", "2000-01-01")));
        assertFalse(criteria.matches(movie(1, null, "drama", "2020-01-01")));
        assertFalse(criteria.matches(movie(1, 121, "drama", "2020-01-01")));
        assertFalse(criteria.matches(movie(1, 100, "drama", "")));
        assertFalse(criteria.matches(movie(1, 100, "drama,horror", "2020-01-01")));
        assertFalse(criteria.matches(movie(1, 100, "", "2020-01-01")));
        assertTrue(criteria.matches(movie(1, 100, "not-horror", "2020-01-01")));
    }

    @Test void unsupportedCompoundConstraintStopsBeforePrivateListOrRecommendationReads() {
        var client = mock(AiClient.class); var rec = mock(CatalogRecommendations.class);
        var interactions = mock(ChatInteractionReader.class); var details = mock(CatalogDetails.class);
        String first = "내 찜 목록 보여주고", second = " 잔인한 장면 없는 영화 추천";
        var list = new ChatIntent(ChatIntent.Route.LIST_BOOKMARK, ChatIntent.Media.ANY, null, null, null, null, null, first);
        var recommend = new ChatIntent(ChatIntent.Route.RECOMMEND, ChatIntent.Media.ANY, null, null, null, null, null, second);
        when(client.classify(anyList(), any())).thenReturn(new ChatPlan(List.of(list, recommend), false,
                List.of(c(AVOID, "violence", "잔인한 장면 없는", "잔인"))));
        var result = new ChatRouter(client, rec, interactions, details, new AiProperties()).reply(7,
                List.of(new AiMessage(AiMessage.Role.USER, first + second)));
        assertTrue(result.message().contains("확인하거나 보장할 수 없는 조건"));
        verifyNoInteractions(rec, interactions, details);
    }
}
