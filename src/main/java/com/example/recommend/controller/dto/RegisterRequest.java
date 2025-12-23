package com.example.recommend.controller.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Getter
@Setter
public class RegisterRequest {

    private String email;
    private String password;
    private String nickname;

    // STEP 3 필드 (지금은 비워둬도 됨)
    private String realName;
    private LocalDate birthDate;
    private String gender;
    private String phone;
}
