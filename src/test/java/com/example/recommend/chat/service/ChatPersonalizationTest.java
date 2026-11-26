package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.domain.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import({ChatInteractionReader.class, CatalogRetriever.class})
class ChatPersonalizationTest {
    @Autowired EntityManager em;
    @Autowired ChatInteractionReader reader;
    @Autowired CatalogRetriever retriever;

    User user(String name) {
        var u = User.builder().email(name + "@example.com").nickname(name).password("hash").build();
        em.persist(u); return u;
    }
    Content content(String title, String type, String genres, double rating) {
        var c = Content.builder().title(title).type(type).genres(genres).rating(rating).build();
        em.persist(c); return c;
    }
    void event(User u, Content c, InteractionType type, int age) {
        em.persist(UserContentInteraction.builder().user(u).content(c).eventType(type)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0).minusMinutes(age)).build());
    }
    List<CatalogCandidate> find(User u, String question) {
        em.flush();
        return retriever.retrievePersonalized(u.getId(), List.of(new AiMessage(AiMessage.Role.USER, question)), reader.taste(u.getId()));
    }

    @Test void ownerOnlyDeduplicatedWeightsUseAliasesAndIgnoreViewsWithoutWrites() {
        var owner = user("owner"); var other = user("other");
        var liked = content("liked", "MOVIE", "Science Fiction, SF, Thriller", 1);
        var disliked = content("disliked", "MOVIE", "Thriller", 1);
        event(owner, liked, InteractionType.LIKE, 1); event(owner, liked, InteractionType.LIKE, 2);
        event(owner, liked, InteractionType.BOOKMARK, 3); event(owner, disliked, InteractionType.DISLIKE, 4);
        event(owner, disliked, InteractionType.VIEW, 0); event(other, liked, InteractionType.DISLIKE, 0);
        em.flush();
        var taste = reader.taste(owner.getId());
        assertEquals(Map.of("sf", 5, "스릴러", 2), taste.weights());
        assertEquals(Map.of("sf", -3, "스릴러", -3), reader.taste(other.getId()).weights());
        assertEquals(6, em.createQuery("select count(u) from UserContentInteraction u", Long.class).getSingleResult());
        assertThrows(ChatException.class, () -> reader.taste(0));
    }

    @Test void ranksAcrossCatalogBeforeLimitAndExcludesReactionsWhileKeepingOtherAccountsWorks() {
        var owner = user("owner"); var other = user("other");
        var anchor = content("anchor", "MOVIE", "SF", 10);
        var bookmark = content("bookmark", "MOVIE", "Comedy", 10);
        var dislike = content("dislike", "MOVIE", "Horror", 10);
        event(owner, anchor, InteractionType.LIKE, 0);
        event(owner, bookmark, InteractionType.BOOKMARK, 1);
        event(owner, dislike, InteractionType.DISLIKE, 2);
        for (int i = 0; i < 14; i++) content("popular" + i, "MOVIE", "Horror", 10);
        var best = content("new sf", "MOVIE", "Science Fiction", 1);
        var second = content("new comedy", "MOVIE", "Comedy", 2);
        var mixed = content("mixed", "MOVIE", "Science Fiction, Horror", 9);
        event(other, best, InteractionType.DISLIKE, 0);
        var found = find(owner, "내가 좋아요를 누른 장르 기반 영화 추천해줘");
        assertEquals(12, found.size());
        assertEquals(List.of(best.getId(), second.getId(), mixed.getId()), found.stream().limit(3).map(CatalogCandidate::id).toList());
        assertTrue(found.stream().noneMatch(c -> Set.of(anchor.getId(), bookmark.getId(), dislike.getId()).contains(c.id())));
        assertTrue(reader.taste(owner.getId()).reason(mixed.getGenres()).contains("일치가 없어"));
    }

    @Test void explicitTypeAndLexicalTermsStillFilterAndUnknownTitleDoesNotFallBack() {
        var owner = user("owner");
        event(owner, content("anchor", "MOVIE", "Thriller", 9), InteractionType.LIKE, 0);
        content("tv", "TV", "Thriller", 10);
        content("thriller", "MOVIE", "Thriller", 9);
        var comedy = content("comedy", "MOVIE", "Comedy", 1);
        assertEquals(List.of(comedy.getId()), find(owner, "내 취향에 맞는 코미디 영화 추천해줘").stream().map(CatalogCandidate::id).toList());
        assertTrue(find(owner, "내 취향에 맞는 없는제목xyz 영화 추천").isEmpty());
    }

    @Test void coldStartUsesCatalogRankingAndNullGenresHaveHonestReason() {
        var owner = user("owner");
        var popular = content("popular", "MOVIE", null, 9);
        content("other", "MOVIE", "Comedy", 1);
        assertEquals(popular.getId(), find(owner, "내 취향에 맞는 영화 추천").get(0).id());
        assertTrue(reader.taste(owner.getId()).summary().contains("기록이 부족"));
        assertTrue(reader.taste(owner.getId()).reason(null).contains("일치가 없어"));
    }

    @Test void boundsRecentRowsAndGenreTermsButExcludesOldReactionsToo() {
        var owner = user("owner");
        var old = content("old disliked", "MOVIE", "Horror", 10);
        event(owner, old, InteractionType.DISLIKE, 200);
        for (int i = 0; i < 100; i++) {
            event(owner, content("anchor" + i, "MOVIE", "genre" + i, 10), InteractionType.LIKE, i);
        }
        var fresh = content("fresh", "MOVIE", "Horror", 1);
        em.flush();
        var taste = reader.taste(owner.getId());
        assertEquals(16, taste.weights().size());
        assertFalse(taste.weights().containsKey("공포"));
        assertEquals(List.of(fresh.getId()), find(owner, "개인화 영화 추천").stream().map(CatalogCandidate::id).toList());
    }

    @Test void searchPhraseCleanupPreservesTitleWordsAfterSemanticRouting() {
        assertTrue(ChatTaste.searchText("내 취향에 맞는 내부자들 영화 추천").contains("내부자들"));
        assertFalse(ChatTaste.searchText("내가 좋아요를 누른 장르 기반 작품 추천해줘").contains("좋아요"));
    }
}
