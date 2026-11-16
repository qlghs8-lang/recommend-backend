package com.example.recommend.repository;

import com.example.recommend.admin.service.AdminContentService;
import com.example.recommend.domain.*;
import com.example.recommend.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.transaction.TestTransaction;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import({RelatedDataDeletion.class, UserService.class, AdminContentService.class,
        DeletionIntegrationTest.Dependencies.class})
class DeletionIntegrationTest {
    @TestConfiguration static class Dependencies {
        @Bean PasswordEncoder encoder() { return mock(PasswordEncoder.class); }
        @Bean PhoneVerificationService phones() { return mock(PhoneVerificationService.class); }
    }
    @Autowired EntityManager em;
    @Autowired UserService users;
    @Autowired AdminContentService contents;

    private record Fixture(Long user, Long content, String email) {}

    private Fixture fixture(String suffix) {
        var user = User.builder().email(suffix + "@example.com").nickname(suffix).password("hash").build();
        em.persist(user);
        var content = Content.builder().title(suffix).type("MOVIE").viewCount(0L).build();
        em.persist(content);
        var now = LocalDateTime.now();
        em.persist(TermsAgreement.builder().user(user).termKey("service").agreed(true).agreedAt(now).build());
        for (var type : InteractionType.values()) {
            em.persist(UserContentInteraction.builder().user(user).content(content).eventType(type).createdAt(now).build());
        }
        var log = RecommendLog.builder().userId(user.getId()).contentId(content.getId())
                .source("CONTENT_BASED").createdAt(now).exposedAt(now).build();
        em.persist(log);
        em.persist(RecommendClickLog.builder().userId(user.getId()).contentId(content.getId())
                .recommendLogId(log.getId()).clickedAt(now).build());
        em.flush();
        return new Fixture(user.getId(), content.getId(), user.getEmail());
    }

    private long count(String entity) {
        return em.createQuery("select count(x) from " + entity + " x", Long.class).getSingleResult();
    }

    @Test void accountDeletionRemovesDependenciesAndPreservesOtherAccountAndCatalog() {
        var target = fixture("target");
        var other = fixture("other");
        users.deleteByEmail(target.email());
        em.clear();
        assertNull(em.find(User.class, target.user()));
        assertNotNull(em.find(User.class, other.user()));
        assertEquals(2, count("Content"));
        assertEquals(1, count("TermsAgreement"));
        assertEquals(InteractionType.values().length, count("UserContentInteraction"));
        assertEquals(1, count("RecommendLog"));
        assertEquals(1, count("RecommendClickLog"));
        users.deleteByEmail(target.email()); // Existing idempotent account contract.
    }

    @Test void contentDeletionPreservesAccountsTermsAndOtherContent() {
        var target = fixture("target");
        fixture("other");
        contents.delete(target.content());
        em.clear();
        assertNull(em.find(Content.class, target.content()));
        assertEquals(2, count("User"));
        assertEquals(2, count("TermsAgreement"));
        assertEquals(1, count("Content"));
        assertEquals(InteractionType.values().length, count("UserContentInteraction"));
        assertEquals(1, count("RecommendLog"));
        assertEquals(1, count("RecommendClickLog"));
        assertThrows(IllegalArgumentException.class, () -> contents.delete(target.content()));
    }

    @Test void rollbackRestoresParentAndAllRelatedData() {
        var target = fixture("rollback");
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
        users.deleteByEmail(target.email());
        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();
        assertNotNull(em.find(User.class, target.user()));
        assertEquals(1, count("TermsAgreement"));
        assertEquals(InteractionType.values().length, count("UserContentInteraction"));
        assertEquals(1, count("RecommendLog"));
        assertEquals(1, count("RecommendClickLog"));
        users.deleteByEmail(target.email());
        contents.delete(target.content());
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
    }
}
