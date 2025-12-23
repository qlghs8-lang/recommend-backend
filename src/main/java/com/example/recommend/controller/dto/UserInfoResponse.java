package com.example.recommend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class UserInfoResponse {
    private String email;
    private String nickname;
    private LocalDateTime createdAt;
    private String profileImageUrl;
}
