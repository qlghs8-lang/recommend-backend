package com.example.recommend.controller.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record UserInfoResponse(
        String email,
        String nickname,
        LocalDateTime createdAt,
        String profileImageUrl,
        String role,

        // ✅ 선택 정보
        String realName,
        LocalDate birthDate,
        String gender,
        String phone,
        boolean phoneVerified,
        String verifiedPhone,

        // ✅ 온보딩(있으면 편함)
        boolean onboardingDone,
        List<String> preferredGenres
) {}
