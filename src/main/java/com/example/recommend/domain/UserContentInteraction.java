package com.example.recommend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_content_interactions",
        indexes = {
                @Index(name = "idx_uci_user", columnList = "user_id"),
                @Index(name = "idx_uci_content", columnList = "content_id"),
                @Index(name = "idx_uci_type", columnList = "eventType"),
                @Index(name = "idx_uci_created_at", columnList = "createdAt")
        }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserContentInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InteractionType eventType;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
