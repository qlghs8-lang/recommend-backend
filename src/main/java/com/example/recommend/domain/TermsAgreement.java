package com.example.recommend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "user_terms_agreements",
    indexes = {
        @Index(name = "idx_terms_user", columnList = "user_id"),
        @Index(name = "idx_terms_key", columnList = "termKey")
    }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TermsAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // service / privacy / age14 / marketing
    @Column(nullable = false, length = 30)
    private String termKey;

    @Column(nullable = false)
    private Boolean agreed;

    @Column(nullable = false)
    private LocalDateTime agreedAt;
}

