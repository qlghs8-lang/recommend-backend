package com.example.recommend.dto.user;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class UserExtraInfoRequest {

    private String realName;
    private LocalDate birthDate;
    private String gender;     // "M" / "F"
    private String phone;
}
