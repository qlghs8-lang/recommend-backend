package com.example.recommend.chat.service;

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
@Import(ChatInteractionReader.class)
class ChatInteractionReaderTest {
    @Autowired EntityManager em;
    @Autowired ChatInteractionReader reader;

    private User user(String label) {
        var user = User.builder().email(label + "@example.com").nickname(label).password("hash").build();
        em.persist(user); return user;
    }
    private Content content(String title) {
        var content = Content.builder().title(title).type("MOVIE").build();
        em.persist(content); return content;
    }
    private void event(User user, Content content, InteractionType type) {
        em.persist(UserContentInteraction.builder().user(user).content(content).eventType(type)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
    }

    @Test void projectsOnlyOwnRequestedReactionTypesWithoutWritesOrSensitiveMetadata() {
        var owner = user("owner"); var other = user("other");
        var first = content("First"); var second = content("Second"); var unrelated = content("Unrelated");
        event(owner, first, InteractionType.LIKE); event(owner, first, InteractionType.LIKE);
        event(owner, first, InteractionType.BOOKMARK); event(owner, first, InteractionType.VIEW);
        event(other, first, InteractionType.DISLIKE); event(other, second, InteractionType.BOOKMARK);
        event(owner, second, InteractionType.VIEW); event(owner, unrelated, InteractionType.DISLIKE);
        em.flush(); em.clear();
        var before = em.createQuery("select count(u) from UserContentInteraction u", Long.class).getSingleResult();
        var result = reader.read(owner.getId(), new LinkedHashSet<>(List.of(first.getId(), second.getId())));
        assertEquals(Map.of(first.getId(), List.of("BOOKMARK", "LIKE"), second.getId(), List.of()), result);
        var otherResult = reader.read(other.getId(), Set.of(first.getId()));
        assertEquals(Map.of(first.getId(), List.of("DISLIKE")), otherResult);
        assertEquals(before, em.createQuery("select count(u) from UserContentInteraction u", Long.class).getSingleResult());
    }

    @Test void rejectsInvalidIdentityAndUnboundedOrInvalidSelections() {
        assertThrows(ChatException.class, () -> reader.read(0, Set.of(1L)));
        assertThrows(ChatException.class, () -> reader.read(1, null));
        assertThrows(ChatException.class, () -> reader.read(1, Set.of()));
        assertThrows(ChatException.class, () -> reader.read(1, Set.of(-1L)));
        var ids = new HashSet<Long>();
        for (long id = 1; id <= 13; id++) ids.add(id);
        assertThrows(ChatException.class, () -> reader.read(1, ids));
    }

    @Test void listsCanonicalDistinctOwnWorksWithMediaFilterAndNoWrites() {
        var owner = user("owner"); var other = user("other");
        var first = content("First"); var second = content("Second"); second.setType("TV");
        var hidden = content("Other account only");
        event(owner, first, InteractionType.DISLIKE); event(owner, first, InteractionType.DISLIKE);
        event(owner, second, InteractionType.DISLIKE); event(owner, hidden, InteractionType.VIEW);
        event(other, hidden, InteractionType.DISLIKE);
        em.flush(); em.clear();
        var result = reader.list(owner.getId(), InteractionType.DISLIKE, com.example.recommend.chat.client.ChatIntent.Media.ANY);
        assertEquals(1, result.split("First", -1).length - 1);
        assertTrue(result.contains("Second")); assertFalse(result.contains("Other account only"));
        assertTrue(result.contains("ID: " + first.getId()));
        assertTrue(result.contains("모두 표시"));
        assertFalse(reader.list(owner.getId(), InteractionType.DISLIKE,
                com.example.recommend.chat.client.ChatIntent.Media.MOVIE).contains("Second"));
        assertTrue(reader.list(owner.getId(), InteractionType.BOOKMARK,
                com.example.recommend.chat.client.ChatIntent.Media.ANY).contains("반응 기록이 없어요"));
        assertEquals(5L, em.createQuery("select count(u) from UserContentInteraction u", Long.class).getSingleResult());
        assertThrows(ChatException.class, () -> reader.list(0, InteractionType.LIKE, com.example.recommend.chat.client.ChatIntent.Media.ANY));
        assertThrows(ChatException.class, () -> reader.list(owner.getId(), InteractionType.VIEW, com.example.recommend.chat.client.ChatIntent.Media.ANY));
    }

    @Test void limitsAfterDeduplicationAndDisclosesTruncation() {
        var owner = user("owner");
        for (int i = 0; i < 22; i++) {
            var work = content("Work " + i);
            event(owner, work, InteractionType.BOOKMARK); event(owner, work, InteractionType.BOOKMARK);
        }
        em.flush(); em.clear();
        var result = reader.list(owner.getId(), InteractionType.BOOKMARK, com.example.recommend.chat.client.ChatIntent.Media.ANY);
        assertEquals(20, result.split("ID:", -1).length - 1);
        assertTrue(result.contains("전체 목록이 아니에요"));
        assertFalse(result.contains("(ID: 0)"));
        assertTrue(result.indexOf("Work 21") < result.indexOf("Work 20"));
        assertFalse(ChatInteractionReader.providerHistory(result).contains("Work"));
        assertFalse(ChatInteractionReader.providerHistory(result).contains("ID:"));
    }
}
