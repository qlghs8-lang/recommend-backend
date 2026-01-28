package com.example.recommend.admin.controller;

import com.example.recommend.admin.dto.AdminRecommendLogRow;
import com.example.recommend.admin.dto.AdminSourceStatsRow;
import com.example.recommend.admin.service.AdminRecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
public class AdminRecommendController {

    private final AdminRecommendService adminRecommendService;

    @GetMapping("/recommend-logs")
    public Page<AdminRecommendLogRow> recommendLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,

            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean clicked,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long contentId
    ) {
        return adminRecommendService.getRecommendLogs(page, size, days, source, clicked, userId, contentId);
    }

    @GetMapping("/recommend-dashboard")
    public AdminRecommendService.DashboardResponse dashboard(
            @RequestParam(defaultValue = "7") int days
    ) {
        return adminRecommendService.getDashboard(days);
    }

    /**
     * ✅ by-source
     * - impressions / uniqueClicks / totalClicks / ctrUnique / ctrTotal
     */
    @GetMapping("/recommend-stats/by-source")
    public List<AdminSourceStatsRow> bySource(
            @RequestParam(defaultValue = "7") int days
    ) {
        return adminRecommendService.getBySource(days);
    }
}
