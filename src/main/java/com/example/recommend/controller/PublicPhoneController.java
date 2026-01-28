package com.example.recommend.controller;

import com.example.recommend.service.PhoneVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/public/phone")
public class PublicPhoneController {

    private final PhoneVerificationService phoneVerificationService;

    public static class PhoneRequest {
        private String phone;
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
    }

    public static class PhoneVerifyRequest {
        private String phone;
        private String code;
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }

    @PostMapping("/request")
    public ResponseEntity<?> request(@RequestBody PhoneRequest req) {
        var pv = phoneVerificationService.request(req == null ? null : req.getPhone());

        // ✅ 개발 중이면 코드도 내려줘서 Postman/프론트 테스트 편하게 (운영 전환 시 제거)
        return ResponseEntity.ok("인증번호 발송(개발코드): " + pv.getCode());
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody PhoneVerifyRequest req) {
        phoneVerificationService.verify(
                req == null ? null : req.getPhone(),
                req == null ? null : req.getCode()
        );
        return ResponseEntity.ok("휴대폰 인증 완료");
    }
}
