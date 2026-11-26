package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.domain.Content;
import com.example.recommend.repository.ContentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import java.util.List;
import java.util.Set;
import java.time.LocalDate;
import static com.example.recommend.chat.client.RecommendationConstraint.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import(CatalogRetriever.class)
class CatalogRetrieverTest {
    @Autowired CatalogRetriever retriever;
    @Autowired ContentRepository contents;

    Content save(String title, String type, String genres, String overview, double rating) {
        return contents.saveAndFlush(Content.builder().title(title).type(type).genres(genres).overview(overview)
                .rating(rating).build());
    }
    List<CatalogCandidate> find(String text) {
        return retriever.retrieve(List.of(new AiMessage(AiMessage.Role.USER, text)));
    }
    RecommendationConstraint c(RecommendationConstraint.Kind kind, String value, String... terms) {
        return new RecommendationConstraint(kind, value, "요청", List.of(terms));
    }
    List<CatalogCandidate> find(RecommendationConstraint... constraints) {
        return retriever.retrieve(List.of(new AiMessage(AiMessage.Role.USER, "자연어 요청")),
                new RecommendationCriteria(List.of(constraints), Set.of(), Set.of(), false));
    }

    @Test void koreanGenreFindsEnglishCatalogAndEnforcesMovieType() {
        var movie = save("우주 탐험", "MOVIE", "Science Fiction", "space", 7);
        save("우주 시리즈", "TV", "Science Fiction", "space", 10);
        save("코미디", "MOVIE", "Comedy", "funny", 9);
        assertEquals(List.of(movie.getId()), find("SF 영화를 추천해줘").stream().map(CatalogCandidate::id).toList());
    }

    @Test void titleMatchOutranksOverviewAndMissingTermsDoNotFallBackToUnrelatedTitles() {
        var exact = save("우주", "MOVIE", "", "", 1);
        save("다른 제목", "MOVIE", "", "우주", 10);
        assertEquals(exact.getId(), find("우주 영화 추천").get(0).id());
        assertTrue(find("없는제목xyz 영화 추천").isEmpty());
        assertTrue(find("xyz' OR 1=1 --").isEmpty());
    }

    @Test void boundsCandidateCountAndEveryProviderFieldWithDeterministicOrder() {
        for (int i = 0; i < 15; i++) save("작품" + i, "MOVIE", null, "🎬".repeat(1000), i);
        var found = find("영화 추천");
        assertEquals(12, found.size());
        assertEquals("작품14", found.get(0).title());
        assertEquals(400, found.get(0).overview().codePointCount(0, found.get(0).overview().length()));
        assertEquals("", found.get(0).genres());
    }

    @Test void followUpUsesOnlyRecentUserQuestionAndExplicitNewGenreReplacesOldGenre() {
        var thriller = save("긴장", "MOVIE", "Thriller", "", 8);
        var comedy = save("웃음", "MOVIE", "Comedy", "", 7);
        var context = List.of(new AiMessage(AiMessage.Role.USER, "스릴러 영화 추천"),
                new AiMessage(AiMessage.Role.ASSISTANT, "코미디"), new AiMessage(AiMessage.Role.USER, "다른 것 추천해줘"));
        assertEquals(List.of(thriller.getId()), retriever.retrieve(context).stream().map(CatalogCandidate::id).toList());
        var changed = List.of(context.get(0), context.get(1), new AiMessage(AiMessage.Role.USER, "코미디 영화 추천"));
        assertEquals(List.of(comedy.getId()), retriever.retrieve(changed).stream().map(CatalogCandidate::id).toList());
    }

    @Test void winterRequestRequiresVisibleSeasonalEvidenceInsteadOfNoisyRequestWords() {
        save("그림 수업", "TV", "Family", "편안한 목소리로 그림을 소개하며 보면 즐거운 수업", 10);
        save("가족 놀이", "TV", "Family", "따뜻하고 편안한 일상을 소개한다", 10);
        save("추적", "MOVIE", "Thriller", "눈앞의 단서를 따라 눈물을 흘린다", 9);
        save("긴 이야기", "MOVIE", "Drama", "가".repeat(400) + "겨울", 9);
        var winter = save("하얀 마을", "MOVIE", "Drama", "눈 덮인 마을에서 겨울을 보내는 가족", 1);
        var christmas = save("명절 이야기", "TV", "Family", "크리스마스를 준비하는 가족", 2);
        for (String question : List.of("겨울에 보면 좋을 작품 소개 해줘", "겨울엔 추천해줘", "겨울철 작품 추천", "winter recommendations")) {
            assertEquals(java.util.Set.of(winter.getId(), christmas.getId()),
                    retriever.retrieve(List.of(new AiMessage(AiMessage.Role.USER, question)),
                            new RecommendationCriteria(List.of(c(SETTING, "winter", "겨울", "크리스마스", "설경", "눈보라")), Set.of(), Set.of(), false))
                            .stream().map(CatalogCandidate::id).collect(java.util.stream.Collectors.toSet()), question);
        }
        assertTrue(find(c(SETTING, "winter", "겨울"), c(GENRE_ALL, "thriller"), c(MEDIA, "MOVIE")).isEmpty());
    }

    @Test void winterCannotBeRelaxedByGenericFollowupOrMovieTypeChange() {
        var movie = save("설경", "MOVIE", "Drama", "겨울 이야기", 2);
        var tv = save("성탄절", "TV", "Drama", "크리스마스 이야기", 1);
        save("다른 영화", "MOVIE", "Drama", "편안한 이야기", 10);
        var previous = new AiMessage(AiMessage.Role.USER, "겨울 시리즈 추천");
        assertEquals(List.of(tv.getId()), retriever.retrieve(List.of(previous,
                new AiMessage(AiMessage.Role.USER, "다른거 추천")), new RecommendationCriteria(List.of(c(SETTING, "winter", "겨울", "크리스마스"),
                        c(MEDIA, "TV")), Set.of(), Set.of(), false)).stream().map(CatalogCandidate::id).toList());
        assertEquals(List.of(movie.getId()), retriever.retrieve(List.of(previous,
                new AiMessage(AiMessage.Role.USER, "다른 영화 추천")), new RecommendationCriteria(List.of(c(SETTING, "winter", "겨울", "크리스마스"),
                        c(MEDIA, "MOVIE")), Set.of(), Set.of(), false)).stream().map(CatalogCandidate::id).toList());
    }

    Content timed(String title, String type, String genres, int year, Integer movie, Integer episode, String overview, double rating) {
        var work = Content.builder().title(title).type(type).genres(genres).releaseDate(LocalDate.of(year, 1, 1))
                .runtimeMinutes(movie).episodeRuntimeMinutes(episode).overview(overview).rating(rating).build();
        return contents.saveAndFlush(work);
    }

    @Test void combinedNumericGenreExclusionAndMoodCriteriaKeepEveryHardFilter() {
        var match = timed("우정", "MOVIE", "comedy,drama", 2015, 90, null, "유쾌한 친구들의 도전과 희망", 1);
        timed("공포", "MOVIE", "comedy,horror", 2020, 80, null, "유쾌한 친구", 10);
        timed("장편", "MOVIE", "comedy", 2020, 121, null, "유쾌한 친구", 10);
        timed("미등록", "MOVIE", "comedy", 2020, null, null, "유쾌한 친구", 10);
        timed("옛날", "MOVIE", "comedy", 1990, 90, null, "유쾌한 친구", 10);
        timed("시리즈", "TV", "comedy", 2020, 90, 30, "유쾌한 친구", 10);
        assertEquals(List.of(match.getId()), find(c(MEDIA, "MOVIE"), c(GENRE_ALL, "comedy"), c(GENRE_EXCLUDE, "horror"),
                c(YEAR_MIN, "2000"), c(MINUTES_MAX, "120"), c(MOOD, "uplifting", "유쾌", "희망")).stream().map(CatalogCandidate::id).toList());
    }

    @Test void durationBoundariesAndEpisodeRuntimeNeverUseUnknownZeroOrMovieRuntimeAsEpisodeData() {
        var shortMovie = timed("단편", "MOVIE", "drama", 2020, 119, null, "이야기", 1);
        timed("두 시간", "MOVIE", "drama", 2020, 120, null, "이야기", 10);
        timed("오류", "MOVIE", "drama", 2020, 0, null, "이야기", 10);
        var episode = timed("짧은 회차", "TV", "drama", 2020, null, 20, "이야기", 2);
        timed("영화 길이만 있는 TV", "TV", "drama", 2020, 10, null, "이야기", 10);
        assertEquals(List.of(shortMovie.getId()), find(c(MINUTES_MAX, "119")).stream().map(CatalogCandidate::id).toList());
        assertEquals(List.of(episode.getId()), find(c(EPISODE_MINUTES_MAX, "20")).stream().map(CatalogCandidate::id).toList());
        assertEquals(List.of(episode.getId()), find(c(ORDER, "SHORTER_EPISODE")).stream().map(CatalogCandidate::id).toList());
    }

    @Test void settingsAreDynamicAndSubjectiveTermsRankWithoutPretendingToBeHardFacts() {
        var summer = timed("바다 여행", "MOVIE", "drama", 2020, 90, null, "여름 해변에서 친구들과 유쾌한 모험", 1);
        var winter = timed("눈 마을", "MOVIE", "drama", 2020, 90, null, "겨울의 쓸쓸한 이별", 10);
        assertEquals(List.of(summer.getId()), find(c(SETTING, "summer", "여름", "해변")).stream().map(CatalogCandidate::id).toList());
        var mood = find(c(MOOD, "cheerful", "유쾌", "모험"));
        assertEquals(List.of(summer.getId(), winter.getId()), mood.stream().map(CatalogCandidate::id).toList());
        assertTrue(find(c(SETTING, "literal SQL metacharacters", "100%_literal")).isEmpty());
    }

    @Test void genreAlternativesAndTrustedReferenceFilteringApplyBeforeTheCandidateLimit() {
        var a = save("웃음", "MOVIE", "comedy", "", 1);
        var b = save("사랑", "MOVIE", "romance", "", 2);
        save("혼합 공포", "MOVIE", "comedy,horror", "", 10);
        assertEquals(Set.of(a.getId(), b.getId()), find(c(GENRE_ANY, "comedy"), c(GENRE_ANY, "romance"), c(GENRE_EXCLUDE, "horror"))
                .stream().map(CatalogCandidate::id).collect(java.util.stream.Collectors.toSet()));
        var criteria = new RecommendationCriteria(List.of(c(REFERENCE, "EXCLUDE_RECENT")), Set.of(), Set.of(b.getId()), false);
        assertFalse(retriever.retrieve(List.of(new AiMessage(AiMessage.Role.USER, "다른 작품")), criteria).stream().anyMatch(w -> w.id() == b.getId()));
        criteria = new RecommendationCriteria(List.of(c(REFERENCE, "ONLY_LAST")), Set.of(a.getId()), Set.of(), false);
        assertEquals(List.of(a.getId()), retriever.retrieve(List.of(new AiMessage(AiMessage.Role.USER, "그중")), criteria).stream().map(CatalogCandidate::id).toList());
    }
}
