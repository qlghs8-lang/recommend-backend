package com.example.recommend.admin.dto;

import java.time.LocalDateTime;

public class AdminRecommendLogRow {

    private final Long recommendLogId;
    private final Long userId;
    private final Long contentId;
    private final String contentTitle;
    private final String source;
    private final String reason;
    private final LocalDateTime createdAt;
    private final boolean clicked;

    public AdminRecommendLogRow(Long recommendLogId,
                               Long userId,
                               Long contentId,
                               String contentTitle,
                               String source,
                               String reason,
                               LocalDateTime createdAt,
                               boolean clicked) {
        this.recommendLogId = recommendLogId;
        this.userId = userId;
        this.contentId = contentId;
        this.contentTitle = contentTitle;
        this.source = source;
        this.reason = reason;
        this.createdAt = createdAt;
        this.clicked = clicked;
    }

    public Long getRecommendLogId() { return recommendLogId; }
    public Long getUserId() { return userId; }
    public Long getContentId() { return contentId; }
    public String getContentTitle() { return contentTitle; }
    public String getSource() { return source; }
    public String getReason() { return reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public boolean isClicked() { return clicked; }
}
