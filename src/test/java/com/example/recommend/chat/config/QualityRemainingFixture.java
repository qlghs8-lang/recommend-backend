package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.repository.CatalogSnapshotRepository;
import com.example.recommend.chat.service.*;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static com.example.recommend.chat.config.QualityBaselineTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fixed, fake accounts/reactions in owned H2 only; never touch application accounts. */
final class QualityRemainingFixture {
    static final Path PATH = Path.of("src/test/resources/chat/quality-remaining-reactions-v1.json");
    static final String PIN = "7f678690253a30d4a47aacc1479e60f57f9893d6bafc7d7ad3f9c65947e6eacf";
    static final long OWNER = 9100001, OTHER = 9100002;
    private final DataSource source;
    private boolean installed;
    QualityRemainingFixture(DataSource source) { this.source = source; }

    private Connection connection() throws Exception {
        var c = source.getConnection();
        if (!c.getMetaData().getURL().startsWith("jdbc:h2:mem:")) {
            c.close(); throw new IllegalStateException("Synthetic fixture requires isolated H2");
        }
        return c;
    }
    static void assertNoSyntheticAccountData(String request) {
        for (String marker : List.of("remaining-owner@example.invalid", "remaining-other@example.invalid", "synthetic-unused-password"))
            if (request.contains(marker)) throw new IllegalStateException("Synthetic account data reached a provider request");
    }
    void install() throws Exception {
        if (installed || !PIN.equals(sha(PATH))) throw new IllegalStateException("Fixed synthetic fixture changed or already installed");
        var fixture = JSON.readTree(Files.readAllBytes(PATH));
        assertEquals(OWNER, fixture.get("ownerId").asLong()); assertEquals(OTHER, fixture.get("otherId").asLong());
        try (var c = connection()) {
            c.setAutoCommit(false);
            try {
                try (var q = c.prepareStatement("select count(*) from users where id in (?,?)")) {
                    q.setLong(1, OWNER); q.setLong(2, OTHER);
                    try (var rows = q.executeQuery()) { rows.next(); assertEquals(0, rows.getInt(1), "Never replace preexisting fixture accounts"); }
                }
                try (var q = c.prepareStatement("insert into users(id,email,password,nickname,token_version,phone_verified,onboarding_done,role) values(?,?,?,?,0,false,false,'USER')")) {
                    for (long id : List.of(OWNER, OTHER)) {
                        String label = id == OWNER ? "remaining-owner" : "remaining-other";
                        q.setLong(1, id); q.setString(2, label + "@example.invalid"); q.setString(3, "synthetic-unused-password"); q.setString(4, label); q.addBatch();
                    }
                    assertEquals(2, q.executeBatch().length);
                }
                try (var q = c.prepareStatement("insert into user_content_interactions(user_id,content_id,event_type,created_at) values(?,?,?,?)")) {
                    for (var event : fixture.get("events")) {
                        long id = event.get("userId").asLong(); assertTrue(id == OWNER || id == OTHER);
                        q.setLong(1, id); q.setLong(2, event.get("contentId").asLong());
                        q.setString(3, event.get("eventType").asString()); q.setTimestamp(4, Timestamp.valueOf("2026-11-25 00:00:00")); q.addBatch();
                    }
                    assertEquals(5, q.executeBatch().length);
                }
                c.commit(); installed = true;
            } catch (Exception | AssertionError error) { c.rollback(); throw error; }
        }
    }
    boolean cleanup() {
        if (!installed) return true;
        try (var c = connection()) {
            c.setAutoCommit(false);
            for (String table : List.of("user_content_interactions", "users")) {
                try (var q = c.prepareStatement("delete from " + table + " where " + (table.equals("users") ? "id" : "user_id") + " in (?,?)")) {
                    q.setLong(1, OWNER); q.setLong(2, OTHER); q.executeUpdate();
                }
            }
            c.commit(); installed = false; return true;
        } catch (Exception error) { return false; }
    }
    Map<String, Object> policyReview(List<Long> selected) throws Exception {
        var excluded = selected.stream().filter(id -> Set.of(41L,344L,100L).contains(id)).toList();
        var outside = new ArrayList<Long>();
        try (var c = connection(); var q = c.prepareStatement("select genres from contents where id=?")) {
            for (long id : selected) {
                q.setLong(1, id);
                try (var rows = q.executeQuery()) {
                    if (!rows.next() || Arrays.stream(Objects.toString(rows.getString(1), "").split(","))
                            .map(String::strip).noneMatch(Set.of("adventure","animation","family","music")::contains)) outside.add(id);
                }
            }
        }
        return Map.of("selectedIds", selected, "selectedOwnReactedOrSeenIds", excluded, "outsideOwnLikedGenres", outside,
                "evaluation", selected.isEmpty() ? "NO_SELECTION_REVIEW_REQUIRED" : excluded.isEmpty() && outside.isEmpty() ? "FIXED_SYNTHETIC_FACTS_MATCH" : "FIXED_SYNTHETIC_CONDITION_MISMATCH");
    }
    Map<String, Object> audit(EntityManager em, CatalogRetriever retriever, CatalogSnapshotRepository snapshots) {
        var interactions = new ChatInteractionReader(em); var provider = mock(AiClient.class);
        var recommendations = new CatalogRecommendations(retriever, provider, snapshots, new CatalogToolService(retriever, interactions), interactions);
        var own = interactions.read(OWNER, Set.of(41L,344L,100L,40L,428L));
        assertEquals(List.of("LIKE"), own.get(41L)); assertEquals(List.of("DISLIKE"), own.get(344L));
        assertTrue(own.get(40L).isEmpty() && own.get(428L).isEmpty());
        var forced = new LinkedHashMap<Long, List<Long>>();
        for (long id : List.of(41L,344L,100L,40L,428L)) {
            String type = id == 40 || id == 428 ? "TV" : "MOVIE";
            var criteria = new RecommendationCriteria(List.of(new RecommendationConstraint(RecommendationConstraint.Kind.MEDIA,
                    type, "작품", List.of())), Set.of(id), Set.of(), false);
            forced.put(id, recommendations.reply(OWNER, List.of(new AiMessage(AiMessage.Role.USER, "작품")), true,
                    new TurnBudget(new AiProperties()), criteria).contentIds());
        }
        assertTrue(forced.get(41L).isEmpty() && forced.get(344L).isEmpty());
        assertEquals(List.of(40L), forced.get(40L)); assertEquals(List.of(428L), forced.get(428L));
        verifyNoInteractions(provider);
        return Map.of("scope", "SYNTHETIC_H2_ONLY", "events", 5, "users", 2,
                "ownLikeAndDislikeExcluded", true, "foreignReactionsDoNotExcludeOwnCandidates", true,
                "viewOnlyExcluded", forced.get(100L).isEmpty(), "viewOnlyProbeReturnedIds", forced.get(100L),
                "providerCalls", 0, "interpretation", "Baseline observation; VIEW policy may fail and is not patched");
    }
}
