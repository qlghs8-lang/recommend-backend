package com.example.recommend.service;

public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static void validate(String password) {
        if (password == null || !password.matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{8,72}$")) {
            throw new IllegalArgumentException("비밀번호는 영문과 숫자를 포함한 8~72자리여야 합니다.");
        }
    }
}
