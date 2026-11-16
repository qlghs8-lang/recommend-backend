package com.example.recommend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;

@Service
public class PhoneVerificationService {
    private final PhoneVerificationStore store;

    @Autowired
    public PhoneVerificationService(PhoneVerificationStore store) { this.store = store; }
    // Explicit in-memory construction for isolated tests; Spring uses the configured store.
    public PhoneVerificationService(SmsSender sender) { this(new InMemoryPhoneVerificationStore(sender)); }
    PhoneVerificationService(SmsSender sender, Clock clock) { this(new InMemoryPhoneVerificationStore(sender, clock)); }

    public record RequestResult(String challengeId, int expiresInSeconds, int resendAfterSeconds) {}
    public record VerifyResult(String verificationTicket, int expiresInSeconds) {}

    public static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) return "";
        if (raw.length() > 30 || !raw.matches("[0-9\\s-]+"))
            throw new IllegalArgumentException("휴대폰 번호 형식이 올바르지 않습니다.");
        String phone = raw.replaceAll("[\\s-]", "");
        if (!phone.matches("010[0-9]{8}"))
            throw new IllegalArgumentException("010으로 시작하는 휴대폰 번호 11자리를 입력해주세요.");
        return phone;
    }

    public String registrationOwner(String requesterToken) {
        if (requesterToken == null || !requesterToken.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("인증 요청자 정보가 올바르지 않습니다. 다시 요청해주세요.");
        return "signup:" + requesterToken;
    }

    public RequestResult request(String rawPhone, String owner, String remoteAddress) {
        String phone = normalizePhone(rawPhone);
        if (phone.isEmpty()) throw new IllegalArgumentException("휴대폰 번호를 입력해주세요.");
        return store.request(phone, owner, remoteAddress);
    }
    public VerifyResult verify(String id, String rawPhone, String code, String owner) {
        return store.verify(id, normalizePhone(rawPhone), code, owner);
    }
    public void consume(String ticket, String rawPhone, String owner) {
        store.consume(ticket, normalizePhone(rawPhone), owner);
    }
}
