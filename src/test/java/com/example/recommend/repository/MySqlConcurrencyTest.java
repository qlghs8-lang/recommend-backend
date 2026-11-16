package com.example.recommend.repository;

import com.example.recommend.admin.service.AdminContentService;
import com.example.recommend.domain.*;
import com.example.recommend.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, synthetic fixtures only. The runner creates this dedicated empty schema. */
@EnabledIfSystemProperty(named = "mysqlConcurrency", matches = "true")
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RecommendService.class, RelatedDataDeletion.class, AdminContentService.class,
        com.example.recommend.admin.service.RecommendLogCleanupService.class,
        UserService.class, InteractionService.class, ContentService.class, DeletionIntegrationTest.Dependencies.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MySqlConcurrencyTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { p.load(reader); }
        // Refuse to run destructive Hibernate DDL against any existing tables, even on direct invocation.
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/recommend_concurrency_test", p.getProperty("spring.datasource.username"),
                p.getProperty("spring.datasource.password"));
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='recommend_concurrency_test'")) {
            rows.next();
            if (rows.getInt(1) != 0) throw new IllegalStateException("Test schema must be empty");
        }
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:3306/recommend_concurrency_test?serverTimezone=Asia/Seoul");
        registry.add("spring.datasource.username", () -> p.getProperty("spring.datasource.username"));
        registry.add("spring.datasource.password", () -> p.getProperty("spring.datasource.password"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }
    @Autowired PlatformTransactionManager manager;
    @Autowired UserRepository users;
    @Autowired ContentRepository contents;
    @Autowired RecommendLogRepository logs;
    @Autowired RecommendClickLogRepository clicks;
    @Autowired RecommendService recommendations;
    @Autowired com.example.recommend.admin.service.RecommendLogCleanupService logCleanup;
    @Autowired RelatedDataDeletion deletion;
    @Autowired AdminContentService admin;
    @Autowired UserService accounts;
    @Autowired InteractionService interactions;
    @Autowired jakarta.persistence.EntityManager em;
    TransactionTemplate tx;
    User user;
    Content content;
    RecommendLog log;

    @BeforeEach void fixture() {
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
        tx.executeWithoutResult(s -> {
            user = users.saveAndFlush(User.builder().email(java.util.UUID.randomUUID()+"@test.local")
                    .nickname(java.util.UUID.randomUUID().toString()).password("synthetic").build());
            content = contents.saveAndFlush(Content.builder().title("synthetic").type("MOVIE").viewCount(0L).build());
            em.persist(TermsAgreement.builder().user(user).termKey("service").agreed(true).agreedAt(LocalDateTime.now()).build());
            em.persist(UserContentInteraction.builder().user(user).content(content).eventType(InteractionType.VIEW)
                    .createdAt(LocalDateTime.now()).build());
            log = logs.saveAndFlush(RecommendLog.builder().userId(user.getId()).contentId(content.getId())
                    .source("TEST").createdAt(LocalDateTime.now()).exposedAt(LocalDateTime.now()).build());
        });
    }
    @AfterEach void cleanup() {
        tx.executeWithoutResult(s -> {
            users.findByIdForUpdate(user.getId()).ifPresent(u -> { deletion.forUser(u.getId()); users.deleteById(u.getId()); });
            if (contents.existsById(content.getId())) admin.delete(content.getId());
        });
    }

    // Hold the first transaction open while the second service call attempts its locks.
    void contend(Runnable first, Runnable second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> tx.executeWithoutResult(s -> {
                first.run(); held.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }));
            assertTrue(held.await(5, TimeUnit.SECONDS));
            Future<?> b = pool.submit(() -> { started.countDown(); second.run(); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> b.get(250, TimeUnit.MILLISECONDS));
            release.countDown();
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    private long events(InteractionType type) {
        return tx.execute(s -> em.createQuery("select count(x) from UserContentInteraction x where x.user.id = :user and x.content.id = :content and x.eventType = :type", Long.class)
                .setParameter("user", user.getId()).setParameter("content", content.getId()).setParameter("type", type).getSingleResult());
    }

    private void oldCandidate() {
        tx.executeWithoutResult(s -> {
            var saved = logs.findLockedById(log.getId()).orElseThrow();
            saved.setCreatedAt(LocalDateTime.now().minusDays(100));
            saved.setExposedAt(null);
            logs.saveAndFlush(saved);
        });
    }

    @Test void cleanupWaitsForExposureAndPreservesIt() throws Exception {
        oldCandidate();
        contend(() -> recommendations.logImpression(user, log.getId()),
                () -> assertEquals(0, logCleanup.cleanup(90, 100, false).deletedCount()));
        assertNotNull(logs.findById(log.getId()).orElseThrow().getExposedAt());
    }

    @Test void exposureAfterCleanupCannotRecreateDeletedLog() throws Exception {
        oldCandidate();
        contend(() -> assertEquals(1, logCleanup.cleanup(90, 100, false).deletedCount()),
                () -> assertThrows(com.example.recommend.exception.ResourceNotFoundException.class,
                        () -> recommendations.logImpression(user, log.getId())));
        assertFalse(logs.existsById(log.getId()));
    }

    @Test void cleanupRollbackRestoresCandidate() {
        oldCandidate();
        tx.executeWithoutResult(s -> {
            assertEquals(1, logCleanup.cleanup(90, 100, false).deletedCount());
            s.setRollbackOnly();
        });
        assertTrue(logs.existsById(log.getId()));
    }

    @Test void cleanupPreservesLegacyClickWithoutExposure() {
        oldCandidate();
        tx.executeWithoutResult(s -> clicks.saveAndFlush(RecommendClickLog.builder()
                .userId(user.getId()).contentId(content.getId()).recommendLogId(log.getId())
                .clickedAt(LocalDateTime.now()).build()));
        assertEquals(0, logCleanup.cleanup(90, 100, false).deletedCount());
        assertTrue(logs.existsById(log.getId()));
    }

    @Test void concurrentViewsKeepEveryIncrementAndEvent() throws Exception {
        contend(() -> interactions.view(user, content.getId()), () -> interactions.view(user, content.getId()));
        assertEquals(2L, contents.findById(content.getId()).orElseThrow().getViewCount());
        assertEquals(3, events(InteractionType.VIEW));
    }

    @Test void differentUsersViewingSameContentKeepBothIncrements() throws Exception {
        User other = tx.execute(s -> users.saveAndFlush(User.builder()
                .email(java.util.UUID.randomUUID()+"@test.local")
                .nickname(java.util.UUID.randomUUID().toString()).password("synthetic").build()));
        try {
            contend(() -> interactions.view(user, content.getId()), () -> interactions.view(other, content.getId()));
            assertEquals(2L, contents.findById(content.getId()).orElseThrow().getViewCount());
        } finally { accounts.deleteByEmail(other.getEmail()); }
    }

    @Test void metadataUpdateDoesNotOverwriteConcurrentView() throws Exception {
        var request = new com.example.recommend.admin.dto.AdminContentRequest();
        request.setType("MOVIE");
        request.setTitle("Updated title");
        contend(() -> interactions.view(user, content.getId()), () -> admin.update(content.getId(), request));
        var saved = contents.findById(content.getId()).orElseThrow();
        assertEquals(1L, saved.getViewCount());
        assertEquals("Updated title", saved.getTitle());
    }

    @Test void repeatedLikesAreIdempotent() throws Exception {
        contend(() -> interactions.like(user, content.getId()), () -> interactions.like(user, content.getId()));
        assertEquals(1, events(InteractionType.LIKE));
    }

    @Test void competingPreferencesRemainExclusive() throws Exception {
        contend(() -> interactions.like(user, content.getId()), () -> interactions.dislike(user, content.getId()));
        assertEquals(0, events(InteractionType.LIKE));
        assertEquals(1, events(InteractionType.DISLIKE));
    }

    @Test void twoBookmarkTogglesReturnToInitialState() throws Exception {
        contend(() -> interactions.toggleBookmark(user, content.getId()), () -> interactions.toggleBookmark(user, content.getId()));
        assertEquals(0, events(InteractionType.BOOKMARK));
    }

    @Test void viewRollbackRestoresCounterAndEvents() {
        tx.executeWithoutResult(s -> { interactions.view(user, content.getId()); s.setRollbackOnly(); });
        assertEquals(0L, contents.findById(content.getId()).orElseThrow().getViewCount());
        assertEquals(1, events(InteractionType.VIEW));
    }

    @Test void contentDeletionWaitsForViewAndRemovesEvents() throws Exception {
        contend(() -> interactions.view(user, content.getId()), () -> admin.delete(content.getId()));
        assertEquals(0, events(InteractionType.VIEW));
    }

    @Test void deletedContentRejectsWaitingLike() throws Exception {
        contend(() -> admin.delete(content.getId()), () -> assertThrows(IllegalArgumentException.class,
                () -> interactions.like(user, content.getId())));
        assertEquals(0, events(InteractionType.LIKE));
    }

    @Test void deletedAccountRejectsWaitingBookmark() throws Exception {
        contend(() -> accounts.deleteByEmail(user.getEmail()), () -> assertThrows(
                com.example.recommend.exception.ResourceNotFoundException.class,
                () -> interactions.toggleBookmark(user, content.getId())));
        assertEquals(0, events(InteractionType.BOOKMARK));
    }

    @Test void concurrentRevocationsBothIncrementAndRollbackDoesNotIncrement() throws Exception {
        contend(() -> users.revokeTokens(user.getId()), () -> tx.executeWithoutResult(s -> users.revokeTokens(user.getId())));
        assertEquals(2, users.findById(user.getId()).orElseThrow().getTokenVersion());
        tx.executeWithoutResult(s -> { users.revokeTokens(user.getId()); s.setRollbackOnly(); });
        assertEquals(2, users.findById(user.getId()).orElseThrow().getTokenVersion());
    }

    @Test void duplicateExposurePreservesFirstTimestamp() throws Exception {
        tx.executeWithoutResult(s -> { var r = logs.findById(log.getId()).orElseThrow(); r.setExposedAt(null); logs.saveAndFlush(r); });
        var first = new java.util.concurrent.atomic.AtomicReference<LocalDateTime>();
        contend(() -> { recommendations.logImpression(user, log.getId()); logs.flush();
            em.clear();
            first.set(logs.findById(log.getId()).orElseThrow().getExposedAt()); },
                () -> recommendations.logImpression(user, log.getId()));
        assertEquals(first.get(), logs.findById(log.getId()).orElseThrow().getExposedAt());
    }

    @Test void deletionWaitsForClickThenRemovesIt() throws Exception {
        contend(() -> recommendations.logClick(user, log.getId()), () -> admin.delete(content.getId()));
        assertFalse(logs.existsById(log.getId()));
        assertEquals(0, clicks.count());
    }

    @Test void completedContentDeletionRejectsWaitingClick() throws Exception {
        contend(() -> admin.delete(content.getId()), () -> assertThrows(
                com.example.recommend.exception.ResourceNotFoundException.class,
                () -> recommendations.logClick(user, log.getId())));
        assertEquals(0, clicks.count());
    }

    @Test void deletedUserCannotCreateCandidates() throws Exception {
        contend(() -> accounts.deleteByEmail(user.getEmail()),
                () -> assertThrows(com.example.recommend.exception.ResourceNotFoundException.class,
                        () -> recommendations.forYouWithReasons(user, 12)));
        assertFalse(logs.existsById(log.getId()));
    }

    @Test void deletedContentIsOmittedFromWaitingCandidateGeneration() throws Exception {
        contend(() -> admin.delete(content.getId()),
                () -> assertTrue(recommendations.forYouWithReasons(user, 12).isEmpty()));
        assertEquals(0, logs.count());
    }

    @Test void contentDeletionWaitsForCandidateGenerationAndCleansAllLogs() throws Exception {
        contend(() -> assertEquals(1, recommendations.forYouWithReasons(user, 12).size()),
                () -> admin.delete(content.getId()));
        assertEquals(0, logs.count());
    }

    @Test void userDeletionWaitsForCandidateGenerationAndCleansAllLogs() throws Exception {
        contend(() -> assertEquals(1, recommendations.forYouWithReasons(user, 12).size()),
                () -> accounts.deleteByEmail(user.getEmail()));
        assertEquals(0, logs.count());
        assertFalse(users.existsById(user.getId()));
    }

    @Test void userDeletionRollbackRestoresForeignKeyChildren() {
        tx.executeWithoutResult(s -> { accounts.deleteByEmail(user.getEmail()); s.setRollbackOnly(); });
        assertTrue(users.existsById(user.getId()));
        assertTrue(logs.existsById(log.getId()));
        tx.executeWithoutResult(s -> {
            assertEquals(1L, em.createQuery("select count(x) from TermsAgreement x", Long.class).getSingleResult());
            assertEquals(1L, em.createQuery("select count(x) from UserContentInteraction x", Long.class).getSingleResult());
        });
    }

    @Test void rollbackRestoresContentAndLogs() {
        recommendations.logClick(user, log.getId());
        tx.executeWithoutResult(s -> { admin.delete(content.getId()); s.setRollbackOnly(); });
        assertTrue(contents.existsById(content.getId()));
        assertTrue(logs.existsById(log.getId()));
        assertEquals(1, clicks.count());
    }
}
