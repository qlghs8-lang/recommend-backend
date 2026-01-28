package com.example.recommend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "recommend_click_logs",
        indexes = {
                @Index(name = "idx_rcl_user", columnList = "userId"),
                @Index(name = "idx_rcl_content", columnList = "contentId"),
                @Index(name = "idx_rcl_recommend_log", columnList = "recommendLogId"),
                @Index(name = "idx_rcl_clicked_at", columnList = "clickedAt")
        }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendClickLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long recommendLogId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long contentId;

    @Column(nullable = false)
    private LocalDateTime clickedAt;
}
