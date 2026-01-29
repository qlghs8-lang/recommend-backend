package com.example.recommend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "recommend_logs",
        indexes = {
                @Index(name = "idx_rl_user", columnList = "userId"),
                @Index(name = "idx_rl_content", columnList = "contentId"),
                @Index(name = "idx_rl_source", columnList = "source"),
                @Index(name = "idx_rl_created_at", columnList = "createdAt")
        }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long contentId;

    // CONTENT_BASED / COLLABORATIVE / TRENDING_FALLBACK
    @Column(nullable = false, length = 30)
    private String source;

    @Column(length = 1000)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}

