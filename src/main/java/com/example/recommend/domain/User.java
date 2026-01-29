package com.example.recommend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* ===== 계정 ===== */
    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    /* ===== 프로필 ===== */
    @Column(nullable = false, unique = true)
    private String nickname;

    @Column(nullable = true)
    private String realName;

    @Column(nullable = true)
    private LocalDate birthDate;

    @Column(length = 1)
    private String gender; // M / F

    @Column
    private String phone;

    @Builder.Default
    @Column(nullable = false)
    private Boolean phoneVerified = false;

    @Column(unique = true)
    private String verifiedPhone;

    /* ===== 시스템 ===== */
    private LocalDateTime createdAt;

    private String profileImageUrl;

    @Column(length = 6)
    private String phoneVerifyCode;

    private LocalDateTime phoneVerifyExpireAt;

    /* ===== 온보딩 ===== */
    // ex) "action,drama,thriller"
    @Column(length = 500)
    private String preferredGenres;

    @Builder.Default
    @Column(nullable = false)
    private Boolean onboardingDone = false;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String role = "USER";
}

