package com.example.recommend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "phone_verifications",
       indexes = {
           @Index(name = "idx_phone_verifications_phone", columnList = "phone"),
           @Index(name = "idx_phone_verifications_verified", columnList = "verified")
       }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhoneVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String phone; // normalize된 phone (숫자만 추천)

    @Column(nullable = false, length = 6)
    private String code;

    @Column(nullable = false)
    private LocalDateTime expireAt;

    @Builder.Default
    @Column(nullable = false)
    private Boolean verified = false;

    private LocalDateTime verifiedAt;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
