package com.example.recommend.service;

/** Implementations must atomically enforce limits, failed attempts and one-use proofs. */
public interface PhoneVerificationStore {
    PhoneVerificationService.RequestResult request(String phone, String owner, String remoteAddress);
    PhoneVerificationService.VerifyResult verify(String id, String phone, String code, String owner);
    void consume(String ticket, String phone, String owner);
}
