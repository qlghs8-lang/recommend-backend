package com.example.recommend.repository;

import com.example.recommend.admin.service.RecommendLogCleanupService;
import com.example.recommend.domain.RecommendLog;
import com.example.recommend.domain.RecommendClickLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import(RecommendLogCleanupService.class)
class RecommendLogCleanupTest {
    @Autowired RecommendLogRepository logs;
    @Autowired RecommendClickLogRepository clicks;
    @Autowired RecommendLogCleanupService service;
    @Autowired jakarta.persistence.EntityManager em;

    private RecommendLog log(int age, boolean exposed) {
        return logs.saveAndFlush(RecommendLog.builder().userId(1L).contentId(1L)
                .source("CONTENT_BASED").createdAt(LocalDateTime.now().minusDays(age))
                .exposedAt(exposed ? LocalDateTime.now().minusDays(age) : null).build());
    }

    @Test void previewAndBoundedCleanupPreserveExposuresLegacyClicksAndRecentCandidates() {
        var first = log(100, false);
        var second = log(100, false);
        var exposed = log(100, true);
        var legacy = log(100, false);
        var recent = log(1, false);
        clicks.saveAndFlush(RecommendClickLog.builder().recommendLogId(legacy.getId())
                .userId(1L).contentId(1L).clickedAt(LocalDateTime.now()).build());
        assertEquals(2, service.cleanup(90, 100, true).candidateCount());
        assertEquals(5, logs.count());
        assertEquals(1, service.cleanup(90, 1, false).deletedCount());
        assertFalse(logs.existsById(first.getId()));
        assertEquals(1, service.cleanup(90, 100, false).deletedCount());
        assertFalse(logs.existsById(second.getId()));
        for (var kept : java.util.List.of(exposed, legacy, recent)) assertTrue(logs.existsById(kept.getId()));
        assertEquals(1, clicks.count());
        assertEquals(0, service.cleanup(90, 100, false).deletedCount());
    }

    @Test void deleteRechecksExposureClickAndStrictCutoff() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        var candidate = log(100, false);
        candidate.setExposedAt(LocalDateTime.now());
        logs.saveAndFlush(candidate);
        assertEquals(0, logs.deleteCleanupCandidate(candidate.getId(), cutoff));
        var legacy = log(100, false);
        clicks.saveAndFlush(RecommendClickLog.builder().recommendLogId(legacy.getId())
                .userId(1L).contentId(1L).clickedAt(LocalDateTime.now()).build());
        assertEquals(0, logs.deleteCleanupCandidate(legacy.getId(), cutoff));
        var boundary = log(100, false);
        // Use the persisted precision for an exact boundary comparison.
        em.refresh(boundary);
        assertEquals(0, logs.deleteCleanupCandidate(boundary.getId(), boundary.getCreatedAt()));
    }

    @Test void rejectsUnsafeRetentionAndUnboundedBatch() {
        for (int days : new int[]{0, 29, 3651})
            assertThrows(IllegalArgumentException.class, () -> service.cleanup(days, 100, false));
        for (int limit : new int[]{0, -1, 501})
            assertThrows(IllegalArgumentException.class, () -> service.cleanup(90, limit, false));
    }
}
