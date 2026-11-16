package com.example.recommend.controller;

import com.example.recommend.service.PhoneVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/public/phone")
public class PublicPhoneController {
    private final PhoneVerificationService service;
    public record PhoneRequest(String phone, String requesterToken) {}
    public record PhoneVerifyRequest(String phone, String code, String challengeId, String requesterToken) {}

    @PostMapping("/request")
    public PhoneVerificationService.RequestResult request(@RequestBody PhoneRequest req, HttpServletRequest http) {
        return service.request(req.phone(), service.registrationOwner(req.requesterToken()), http.getRemoteAddr());
    }
    @PostMapping("/verify")
    public PhoneVerificationService.VerifyResult verify(@RequestBody PhoneVerifyRequest req) {
        return service.verify(req.challengeId(), req.phone(), req.code(), service.registrationOwner(req.requesterToken()));
    }
}
