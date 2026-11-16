package com.example.recommend.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import com.example.recommend.service.PhoneVerificationService.RequestResult;
import com.example.recommend.service.PhoneVerificationService.VerifyResult;

/** Development-only, single-process store. */
public class InMemoryPhoneVerificationStore implements PhoneVerificationStore {
    private final SmsSender sender;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new HashMap<>();
    private final Map<String, Ticket> tickets = new HashMap<>();
    private final Map<String, Deque<Instant>> sends = new HashMap<>();

    public InMemoryPhoneVerificationStore(SmsSender sender) { this(sender, Clock.systemUTC()); }
    InMemoryPhoneVerificationStore(SmsSender sender, Clock clock) { this.sender = sender; this.clock = clock; }
    private record Ticket(String owner, String phone, Instant expires) {}
    private static class Challenge {
        final String owner, phone, code;
        final Instant expires;
        int failures;
        Challenge(String owner, String phone, String code, Instant expires) {
            this.owner = owner; this.phone = phone; this.code = code; this.expires = expires;
        }
    }

    public synchronized RequestResult request(String rawPhone, String owner, String remoteAddress) {
        String phone = PhoneVerificationService.normalizePhone(rawPhone);
        if (phone.isEmpty()) throw new IllegalArgumentException("휴대폰 번호를 입력해주세요.");
        Instant now = clock.instant();
        cleanup(now);
        List<String> keys = List.of("phone:" + phone, "owner:" + owner, "ip:" + remoteAddress, "global");
        for (int i = 0; i < keys.size(); i++) {
            Deque<Instant> history = sends.get(keys.get(i));
            int limit = i == 3 ? 200 : i == 2 ? 20 : 5;
            if (history != null && (history.size() >= limit ||
                    (i < 2 && history.getLast().plusSeconds(60).isAfter(now)))) rateLimited();
        }
        if (challenges.size() + tickets.size() >= 2000 || sends.size() >= 5000) rateLimited();
        // Delivery failures count too, so outages cannot bypass throttling.
        keys.forEach(key -> sends.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(now));
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        sender.send(phone, code);
        challenges.values().removeIf(c -> c.owner.equals(owner));
        tickets.values().removeIf(t -> t.owner.equals(owner));
        String id = token();
        challenges.put(id, new Challenge(owner, phone, code, now.plusSeconds(180)));
        return new RequestResult(id, 180, 60);
    }

    public synchronized VerifyResult verify(String id, String rawPhone, String code, String owner) {
        String phone = PhoneVerificationService.normalizePhone(rawPhone);
        Challenge c = challenges.get(id);
        if (c == null || !c.owner.equals(owner) || !c.phone.equals(phone)) invalid();
        if (!c.expires.isAfter(clock.instant())) { challenges.remove(id); invalid(); }
        c.failures++;
        if (code == null || !MessageDigest.isEqual(c.code.getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8))) {
            if (c.failures >= 5) challenges.remove(id);
            invalid();
        }
        challenges.remove(id);
        String ticket = token();
        tickets.put(ticket, new Ticket(owner, phone, clock.instant().plusSeconds(300)));
        return new VerifyResult(ticket, 300);
    }

    /** Atomic consumption: simultaneous attempts can use a ticket only once. */
    public synchronized void consume(String ticket, String rawPhone, String owner) {
        String phone = PhoneVerificationService.normalizePhone(rawPhone);
        Ticket t = tickets.get(ticket);
        if (t == null || !t.owner.equals(owner) || !t.phone.equals(phone) || !t.expires.isAfter(clock.instant())) invalid();
        tickets.remove(ticket);
    }

    private String token() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    private void cleanup(Instant now) {
        challenges.values().removeIf(c -> !c.expires.isAfter(now));
        tickets.values().removeIf(t -> !t.expires.isAfter(now));
        sends.values().forEach(q -> { while (!q.isEmpty() && !q.getFirst().plusSeconds(3600).isAfter(now)) q.removeFirst(); });
        sends.values().removeIf(Deque::isEmpty);
    }
    private void invalid() { throw new IllegalArgumentException("인증 정보가 올바르지 않거나 만료되었습니다. 다시 인증해주세요."); }
    private void rateLimited() { throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "인증 요청이 너무 많습니다. 잠시 후 다시 시도해주세요."); }
}
