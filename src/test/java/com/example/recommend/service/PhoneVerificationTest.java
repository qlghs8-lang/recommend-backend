package com.example.recommend.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PhoneVerificationTest {
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    final TestClock clock = new TestClock();
    String code;
    final PhoneVerificationService service = new PhoneVerificationService((p, c) -> code = c, clock);
    final String phone = "01012345678";
    final String owner = service.registrationOwner("a".repeat(64));
    String request() { return service.request(phone, owner, "127.0.0.1").challengeId(); }

    @Test void bindsOwnerPhoneAndPurposeAndConsumesOnlyOnce() {
        String id = request();
        assertThrows(IllegalArgumentException.class, () -> service.verify(id, phone, code, "user:1"));
        assertThrows(IllegalArgumentException.class, () -> service.verify(id, "01099998888", code, owner));
        var ticket = service.verify(id, "010-1234-5678", code, owner).verificationTicket();
        assertThrows(IllegalArgumentException.class, () -> service.verify(id, phone, code, owner));
        assertThrows(IllegalArgumentException.class, () -> service.consume(ticket, phone, "signup:" + "b".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> service.consume(ticket, "01099998888", owner));
        service.consume(ticket, phone, owner);
        assertThrows(IllegalArgumentException.class, () -> service.consume(ticket, phone, owner));
    }
    @Test void fifthWrongAttemptLocksEvenCorrectCode() {
        String id = request();
        for (int i = 0; i < 5; i++)
            assertThrows(IllegalArgumentException.class, () -> service.verify(id, phone, "invalid", owner));
        assertThrows(IllegalArgumentException.class, () -> service.verify(id, phone, code, owner));
    }
    @Test void exactExpiryRejectsChallengeAndTicket() {
        String id = request();
        clock.advance(180);
        assertThrows(IllegalArgumentException.class, () -> service.verify(id, phone, code, owner));
        String nextId = request();
        String ticket = service.verify(nextId, phone, code, owner).verificationTicket();
        clock.advance(300);
        assertThrows(IllegalArgumentException.class, () -> service.consume(ticket, phone, owner));
    }
    @Test void resendInvalidatesOldChallengeAndTicket() {
        String old = request();
        clock.advance(60);
        String id = request();
        assertThrows(IllegalArgumentException.class, () -> service.verify(old, phone, code, owner));
        String ticket = service.verify(id, phone, code, owner).verificationTicket();
        clock.advance(60);
        request();
        assertThrows(IllegalArgumentException.class, () -> service.consume(ticket, phone, owner));
    }
    @Test void cooldownAndPhoneHourlyLimitCannotBeBypassedByChangingOwner() {
        request();
        assertEquals(429, assertThrows(ResponseStatusException.class, this::request).getStatusCode().value());
        for (int i = 1; i < 5; i++) { clock.advance(60); request(); }
        clock.advance(60);
        assertEquals(429, assertThrows(ResponseStatusException.class,
            () -> service.request(phone, "other", "other-ip")).getStatusCode().value());
        clock.advance(3600);
        assertNotNull(request());
    }
    @Test void ipAndGlobalBudgetsApplyAcrossPhones() {
        for (int i = 0; i < 20; i++) service.request(String.format("010%08d", i), "owner" + i, "shared-ip");
        assertThrows(ResponseStatusException.class, () -> service.request("01099999999", "new", "shared-ip"));
        for (int i = 20; i < 200; i++) service.request(String.format("010%08d", i), "owner" + i, "ip" + i);
        assertThrows(ResponseStatusException.class, () -> service.request("01088888888", "last", "last-ip"));
    }
    @Test void failedDeliveryIsNotSuccessAndStillThrottles() {
        var unavailable = new PhoneVerificationService((p, c) -> {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }, clock);
        assertEquals(503, assertThrows(ResponseStatusException.class,
            () -> unavailable.request(phone, owner, "ip")).getStatusCode().value());
        assertEquals(429, assertThrows(ResponseStatusException.class,
            () -> unavailable.request(phone, owner, "ip")).getStatusCode().value());
    }
    @Test void invalidPhoneAndMissingRequesterAreRejected() {
        for (String invalid : new String[]{"010abc12345678", "123", "+821012345678"})
            assertThrows(IllegalArgumentException.class, () -> service.request(invalid, owner, "ip"));
        assertThrows(IllegalArgumentException.class, () -> service.registrationOwner(null));
    }
    @Test void simultaneousConsumptionHasExactlyOneWinner() throws Exception {
        String id = request();
        String ticket = service.verify(id, phone, code, owner).verificationTicket();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> consume = () -> { start.await(); try { service.consume(ticket, phone, owner); winners.incrementAndGet(); }
            catch (IllegalArgumentException expected) {} return null; };
        try {
            Future<Void> a = pool.submit(consume), b = pool.submit(consume);
            start.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
            assertEquals(1, winners.get());
        } finally { pool.shutdownNow(); }
    }
}
