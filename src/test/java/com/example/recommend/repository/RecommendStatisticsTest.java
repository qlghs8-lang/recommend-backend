package com.example.recommend.repository;

import com.example.recommend.domain.*;
import com.example.recommend.admin.service.AdminRecommendService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
class RecommendStatisticsTest {
    @Autowired RecommendLogRepository logs;
    @Autowired RecommendClickLogRepository clicks;
    @Autowired ContentRepository contents;
    @Autowired UserRepository users;
    @Autowired UserContentInteractionRepository interactions;

    @Test void collaborativeSupportCountsEachUserOnceDespiteMultiplePositiveActions() {
        User first = users.save(User.builder().email("first@test.local").nickname("first").password("hash").build());
        User second = users.save(User.builder().email("second@test.local").nickname("second").password("hash").build());
        Content content = contents.save(Content.builder().title("candidate").type("MOVIE").build());
        for (var user : java.util.List.of(first, second)) {
            for (var event : java.util.List.of(InteractionType.LIKE, InteractionType.BOOKMARK)) {
                interactions.saveAndFlush(UserContentInteraction.builder().user(user).content(content)
                        .eventType(event).createdAt(LocalDateTime.now()).build());
            }
        }
        var result = interactions.countSupportersByContent(java.util.List.of(first.getId(), second.getId()),
                java.util.List.of(InteractionType.LIKE, InteractionType.BOOKMARK));
        assertEquals(1, result.size());
        assertEquals(content.getId(), result.get(0).getContentId());
        assertEquals(2L, result.get(0).getSupporterCount());
    }

    @Test void exposurePersistsThroughLockedLookupAndRepeatPreservesTimestamp() {
        var service = new com.example.recommend.service.RecommendService(contents, interactions, logs, clicks, users);
        User owner = users.saveAndFlush(User.builder().email("exposure@test.local").nickname("exposure").password("hash").build());
        Content content = contents.saveAndFlush(Content.builder().title("exposure").type("MOVIE").build());
        RecommendLog candidate = logs.saveAndFlush(RecommendLog.builder().userId(owner.getId()).contentId(content.getId())
                .source("CONTENT_BASED").createdAt(LocalDateTime.now()).build());
        service.logImpression(owner, candidate.getId());
        logs.flush();
        LocalDateTime first = logs.findLockedById(candidate.getId()).orElseThrow().getExposedAt();
        assertNotNull(first);
        service.logImpression(owner, candidate.getId());
        logs.flush();
        assertEquals(first, logs.findLockedById(candidate.getId()).orElseThrow().getExposedAt());
    }

    RecommendLog log(LocalDateTime exposedAt) {
        return logs.saveAndFlush(RecommendLog.builder().userId(1L).contentId(1L)
                .source("CONTENT_BASED_EXPLOIT").createdAt(LocalDateTime.now().minusDays(20))
                .exposedAt(exposedAt).build());
    }
    void click(RecommendLog log) {
        clicks.saveAndFlush(RecommendClickLog.builder().userId(1L).contentId(1L)
                .recommendLogId(log.getId()).clickedAt(LocalDateTime.now()).build());
    }

    @Test void repeatedClicksDoNotMultiplyImpressionsAndAllViewsUseExposureCohort() {
        LocalDateTime from = LocalDateTime.now().minusDays(7);
        RecommendLog exposed = log(LocalDateTime.now());
        click(exposed); click(exposed); click(exposed);
        log(LocalDateTime.now()); // unclicked exposure
        log(null); // generated but never visible
        click(log(from.minusSeconds(1))); // recent click on an old exposure
        var stat = logs.findSourceStatsSince(from).get(0);
        assertEquals(2, stat.getImpressions());
        assertEquals(1, stat.getUniqueClicks());
        assertEquals(3, stat.getTotalClicks());
        assertEquals(0.5, stat.getCtrUnique());
        assertEquals(1.5, stat.getCtrTotal());
        assertEquals(2, logs.countByExposedAtGreaterThanEqual(from));
        assertEquals(1, logs.countDistinctClicksSince(from));
        assertEquals(3, logs.countTotalClicksSince(from));
        var page = logs.findAdminLogRowsWithFilters(PageRequest.of(0, 10), from, null, null, null, null);
        assertEquals(2, page.getTotalElements());
        assertEquals(1, logs.findAdminLogRowsWithFilters(PageRequest.of(0, 1), from, null, true, null, null).getTotalElements());
        assertEquals(3, logs.findAdminLogRowsWithFilters(PageRequest.of(0, 10), null, null, null, null, null).getTotalElements());
        var dashboard = new AdminRecommendService(logs).getDashboard(7);
        assertEquals(2, dashboard.impressions());
        assertEquals(1, dashboard.clicks());
        assertEquals(3, dashboard.totalClicks());
        assertEquals(0.5, dashboard.ctr());
    }
}
