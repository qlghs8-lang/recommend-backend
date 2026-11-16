package com.example.recommend.service;

/** Implementations must never log or return verification codes. */
public interface SmsSender {
    void send(String phone, String code);
}
