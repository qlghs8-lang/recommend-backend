package com.example.recommend.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Only an explicitly enabled, isolated localhost Redis on port 16379 is used. */
@EnabledIfEnvironmentVariable(named = "RUN_PHONE_REDIS_TESTS", matches = "true")
class RedisPhoneVerificationTest {
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    String prefix, keyPrefix;
    String code;
    final String phone = "01012345678", owner = "signup:" + "a".repeat(64);
    PhoneVerificationService first, second;

    @BeforeEach void setup() {
        connection = new LettuceConnectionFactory("127.0.0.1", 16379);
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        prefix = "phone-test:" + UUID.randomUUID();
        keyPrefix = "{" + prefix + "}:";
        first = service((p, c) -> code = c);
        second = service((p, c) -> code = c);
    }
    PhoneVerificationService service(SmsSender sender) {
        return new PhoneVerificationService(new RedisPhoneVerificationStore(redis, sender, prefix));
    }
    @AfterEach void cleanup() {
        try {
            Set<String> keys = redis.keys(keyPrefix + "*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        } finally { connection.destroy(); }
    }
    String request() { return first.request(phone, owner, "ip").challengeId(); }
    String state() throws Exception { return keyPrefix + "state:" + hash(owner); }
    static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    void ageRateHistory() {
        for (String key : redis.keys(keyPrefix + "rate:*")) {
            for (String member : redis.opsForZSet().range(key, 0, -1))
                redis.opsForZSet().incrementScore(key, member, -61000);
        }
    }

    @Test void sharedStateSurvivesServiceRecreationAndBindsOwnerPhone() {
        String id = request();
        assertThrows(IllegalArgumentException.class, () -> second.verify(id, phone, code, "user:1"));
        assertThrows(IllegalArgumentException.class, () -> second.verify(id, "01099998888", code, owner));
        String ticket = second.verify(id, phone, code, owner).verificationTicket();
        assertThrows(IllegalArgumentException.class, () -> first.verify(id, phone, code, owner));
        assertThrows(IllegalArgumentException.class, () -> first.consume(ticket, "01099998888", owner));
        service((p, c) -> fail()).consume(ticket, phone, owner);
        assertThrows(IllegalArgumentException.class, () -> second.consume(ticket, phone, owner));
    }
    @Test void concurrentConsumptionAcrossInstancesHasOneWinner() throws Exception {
        String id = request();
        String ticket = second.verify(id, phone, code, owner).verificationTicket();
        assertEquals(1, race(() -> first.consume(ticket, phone, owner), () -> second.consume(ticket, phone, owner)));
    }
    @Test void concurrentVerificationHasOneWinner() throws Exception {
        String id = request();
        assertEquals(1, race(() -> first.verify(id, phone, code, owner), () -> second.verify(id, phone, code, owner)));
    }
    @Test void simultaneousRequestsShareCooldown() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        PhoneVerificationService a = service((p, c) -> delivered.incrementAndGet());
        PhoneVerificationService b = service((p, c) -> delivered.incrementAndGet());
        Runnable requestA = () -> { try { a.request(phone, owner, "ip"); } catch (ResponseStatusException e) {
            assertEquals(429, e.getStatusCode().value()); throw new IllegalArgumentException(); } };
        Runnable requestB = () -> { try { b.request(phone, owner, "ip"); } catch (ResponseStatusException e) {
            assertEquals(429, e.getStatusCode().value()); throw new IllegalArgumentException(); } };
        assertEquals(1, race(requestA, requestB));
        assertEquals(1, delivered.get());
    }
    @Test void ipAndGlobalLimitsApplyAcrossDistinctOwnersAndPhones() {
        for (int i = 0; i < 200; i++) {
            if (i == 20) {
                assertEquals(429, assertThrows(ResponseStatusException.class,
                        () -> second.request("01099999999", "extra", "ip-0")).getStatusCode().value());
            }
            first.request(String.format(Locale.ROOT, "010%08d", i), "owner-" + i, "ip-" + i / 20);
        }
        assertEquals(429, assertThrows(ResponseStatusException.class,
                () -> second.request("01099999999", "new-owner", "new-ip")).getStatusCode().value());
    }
    @Test void fifthFailureIsSharedAndCannotBeRolledBack() {
        String id = request();
        for (int i = 0; i < 5; i++) {
            PhoneVerificationService current = i % 2 == 0 ? first : second;
            assertThrows(IllegalArgumentException.class, () -> current.verify(id, phone, "wrong", owner));
        }
        assertThrows(IllegalArgumentException.class, () -> second.verify(id, phone, code, owner));
    }
    @Test void expiryUsesRedisTimeEvenWhenKeyStillExists() throws Exception {
        String id = request();
        redis.opsForHash().put(state(), "expires", "0");
        assertThrows(IllegalArgumentException.class, () -> second.verify(id, phone, code, owner));
        ageRateHistory();
        String nextId = request();
        String ticket = second.verify(nextId, phone, code, owner).verificationTicket();
        redis.opsForHash().put(state(), "ticketExpires", "0");
        assertThrows(IllegalArgumentException.class, () -> first.consume(ticket, phone, owner));
    }
    @Test void failedDeliveryCountsAndPreservesPreviousProof() {
        String id = request();
        String ticket = second.verify(id, phone, code, owner).verificationTicket();
        ageRateHistory();
        PhoneVerificationService failing = service((p, c) -> { throw new IllegalStateException("delivery failed"); });
        assertThrows(IllegalStateException.class, () -> failing.request(phone, owner, "ip"));
        assertEquals(429, assertThrows(ResponseStatusException.class, this::request).getStatusCode().value());
        first.consume(ticket, phone, owner);
    }
    @Test void rateLimitsAreSharedAndResendInvalidatesOldTicket() {
        String id = request();
        String ticket = second.verify(id, phone, code, owner).verificationTicket();
        assertEquals(429, assertThrows(ResponseStatusException.class,
                () -> second.request(phone, "other", "other-ip")).getStatusCode().value());
        for (int i = 1; i < 5; i++) { ageRateHistory(); request(); }
        assertThrows(IllegalArgumentException.class, () -> second.consume(ticket, phone, owner));
        ageRateHistory();
        assertEquals(429, assertThrows(ResponseStatusException.class, this::request).getStatusCode().value());
    }
    @Test void lateDeliveryCannotOverwriteNewChallenge() throws Exception {
        CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1);
        PhoneVerificationService slow = service((p, c) -> {
            sending.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> old = executor.submit(() -> assertEquals(503, assertThrows(ResponseStatusException.class,
                    () -> slow.request(phone, owner, "ip")).getStatusCode().value()));
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            ageRateHistory();
            String latest = request();
            release.countDown();
            old.get(5, TimeUnit.SECONDS);
            assertNotNull(second.verify(latest, phone, code, owner));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void allStoredKeysExpireAndSensitiveValuesAreNotPlaintext() {
        String id = request();
        for (String key : redis.keys(keyPrefix + "*")) {
            assertTrue(redis.getExpire(key) > 0);
            assertFalse(key.contains(phone));
            assertFalse(key.contains(owner));
            if (key.contains(":state:")) {
                var values = redis.opsForHash().entries(key).values();
                assertFalse(values.contains(phone));
                assertFalse(values.contains(code));
                assertFalse(values.contains(id));
            }
        }
    }
    private int race(Runnable a, Runnable b) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : List.of(a, b)) futures.add(executor.submit(() -> {
                try { start.await(); task.run(); winners.incrementAndGet(); }
                catch (IllegalArgumentException expected) { }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
            return winners.get();
        } finally { executor.shutdownNow(); }
    }
}
