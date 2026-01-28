package com.example.recommend.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminSourceStatsRow {
    private final String source;

    private final long impressions;

    /** distinct recommendLogId */
    private final long uniqueClicks;

    /** click log rows (duplicates included) */
    private final long totalClicks;

    private final double ctrUnique;
    private final double ctrTotal;
}
