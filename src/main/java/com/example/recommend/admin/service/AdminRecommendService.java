package com.example.recommend.admin.service;

import com.example.recommend.admin.dto.AdminRecommendLogRow;
import com.example.recommend.admin.dto.AdminSourceStatsRow;
import com.example.recommend.repository.RecommendLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminRecommendService {

    private final RecommendLogRepository recommendLogRepository;

    public Page<AdminRecommendLogRow> getRecommendLogs(
            int page,
            int size,
            Integer days,
            String source,
            Boolean clicked,
            Long userId,
            Long contentId
    ) {
        Pageable pageable = PageRequest.of(page, size);

        LocalDateTime from = null;
        if (days != null) {
            if (days <= 0) {
                from = null;
            } else {
                from = LocalDateTime.now().minusDays(days);
            }
        }

        return recommendLogRepository.findAdminLogRowsWithFilters(
                pageable,
                from,
                source,
                clicked,
                userId,
                contentId
        );
    }

    public DashboardResponse getDashboard(int days) {
        LocalDateTime from = LocalDateTime.now().minusDays(days);

        List<AdminSourceStatsRow> stats = recommendLogRepository.findSourceStatsSince(from);
        long impressions = stats.stream().mapToLong(AdminSourceStatsRow::getImpressions).sum();
        long uniqueClicks = stats.stream().mapToLong(AdminSourceStatsRow::getUniqueClicks).sum();
        long totalClicks = stats.stream().mapToLong(AdminSourceStatsRow::getTotalClicks).sum();
        List<SourceCtr> bySource = stats.stream().map(s -> new SourceCtr(s.getSource(),
                s.getImpressions(), s.getUniqueClicks(), s.getCtrUnique(), s.getTotalClicks())).toList();
        return new DashboardResponse(impressions, uniqueClicks,
                impressions == 0 ? 0 : (double) uniqueClicks / impressions, bySource, totalClicks);
    }

    /**
     * ✅ by-source (권장: 1쿼리로 완결)
     * - impressions
     * - uniqueClicks (distinct recommendLogId)
     * - totalClicks (click log row count)
     * - ctrUnique / ctrTotal
     */
    public List<AdminSourceStatsRow> getBySource(int days) {
        LocalDateTime from = LocalDateTime.now().minusDays(days);

        List<AdminSourceStatsRow> out = recommendLogRepository.findSourceStatsSince(from);

        // Rank by unique CTR; repeated clicks remain a separate volume metric.
        out = new ArrayList<>(out);
        out.sort((a, b) -> Double.compare(b.getCtrUnique(), a.getCtrUnique()));
        return out;
    }

    public record SourceCtr(String source, long impressions, long clicks, double ctr, long totalClicks) {}
    public record DashboardResponse(long impressions, long clicks, double ctr, List<SourceCtr> bySource, long totalClicks) {}
}
