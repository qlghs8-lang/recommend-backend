package com.example.recommend.admin.service;

import com.example.recommend.admin.dto.AdminRecommendLogRow;
import com.example.recommend.admin.dto.AdminSourceStatRow;
import com.example.recommend.admin.dto.AdminSourceStatsRow;
import com.example.recommend.repository.RecommendLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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

        long impressions = recommendLogRepository.countByCreatedAtGreaterThanEqual(from);

        // ✅ total clicks 기준
        long totalClicks = recommendLogRepository.countTotalClicksSince(from);

        double ctr = impressions == 0 ? 0.0 : (double) totalClicks / impressions;

        Map<String, Long> impBySource = recommendLogRepository
                .countImpressionsBySourceSince(from)
                .stream()
                .collect(Collectors.toMap(
                        AdminSourceStatRow::getSource,
                        AdminSourceStatRow::getCount
                ));

        Map<String, Long> clkBySource = recommendLogRepository
                .countTotalClicksBySourceSince(from)
                .stream()
                .collect(Collectors.toMap(
                        AdminSourceStatRow::getSource,
                        AdminSourceStatRow::getCount
                ));

        List<SourceCtr> sourceCtrs = new ArrayList<>();

        Set<String> sources = new HashSet<>();
        sources.addAll(impBySource.keySet());
        sources.addAll(clkBySource.keySet());

        for (String s : sources) {
            long imp = impBySource.getOrDefault(s, 0L);
            long clk = clkBySource.getOrDefault(s, 0L);

            double sCtr = imp == 0 ? 0.0 : (double) clk / imp;

            sourceCtrs.add(new SourceCtr(s, imp, clk, sCtr));
        }

        sourceCtrs.sort((a, b) -> Double.compare(b.ctr(), a.ctr()));

        return new DashboardResponse(impressions, totalClicks, ctr, sourceCtrs);
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

        // ✅ 기본 정렬: ctrTotal 높은 순 (너 정책이 total CTR이면 이게 더 직관적)
        out.sort((a, b) -> Double.compare(b.getCtrTotal(), a.getCtrTotal()));
        return out;
    }

    public record SourceCtr(String source, long impressions, long clicks, double ctr) {}
    public record DashboardResponse(long impressions, long clicks, double ctr, List<SourceCtr> bySource) {}
}
