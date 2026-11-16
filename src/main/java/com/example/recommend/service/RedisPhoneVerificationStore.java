package com.example.recommend.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Redis server time and Lua transitions are shared by all application instances. */
public class RedisPhoneVerificationStore implements PhoneVerificationStore {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("redis/phone-verification.lua"));
        SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final SmsSender sender;
    private final String prefix;
    private final SecureRandom random = new SecureRandom();

    public RedisPhoneVerificationStore(StringRedisTemplate redis, SmsSender sender, String prefix) {
        if (prefix == null || !prefix.matches("[a-zA-Z0-9:_-]{1,100}"))
            throw new IllegalArgumentException("Invalid phone Redis prefix");
        this.redis = redis;
        this.sender = sender;
        // Same cluster hash slot for state and all rate-limit dimensions.
        this.prefix = "{" + prefix + "}:";
    }

    @Override
    public PhoneVerificationService.RequestResult request(String phone, String owner, String remoteAddress) {
        String id = token();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        List<String> keys = List.of(stateKey(owner), prefix + "rate:phone:" + hash(phone),
                prefix + "rate:owner:" + hash(owner), prefix + "rate:ip:" + hash(remoteAddress), prefix + "rate:global");
        long reserved = execute(keys, "reserve", hash(id), hash(phone), hash(id + ":" + code));
        if (reserved == 429) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "인증 요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
        if (reserved != 1) throw unavailable();
        // External delivery never holds a Redis lock. Reservation counts even on failure.
        try {
            sender.send(phone, code);
        } catch (RuntimeException failure) {
            try { execute(List.of(stateKey(owner)), "cancel", hash(id)); }
            catch (RuntimeException ignored) { /* Expiring reservation is unusable for verification. */ }
            throw failure;
        }
        if (execute(List.of(stateKey(owner)), "activate", hash(id)) != 1) throw unavailable();
        return new PhoneVerificationService.RequestResult(id, 180, 60);
    }

    @Override
    public PhoneVerificationService.VerifyResult verify(String id, String phone, String code, String owner) {
        if (id == null || code == null) throw invalid();
        String ticket = token();
        long result = execute(List.of(stateKey(owner)), "verify", hash(id), hash(phone),
                hash(id + ":" + code), hash(ticket));
        if (result != 1) throw invalid();
        return new PhoneVerificationService.VerifyResult(ticket, 300);
    }

    @Override
    public void consume(String ticket, String phone, String owner) {
        if (ticket == null) throw invalid();
        if (execute(List.of(stateKey(owner)), "consume", hash(ticket), hash(phone)) != 1) throw invalid();
    }

    private String stateKey(String owner) { return prefix + "state:" + hash(owner); }
    private long execute(List<String> keys, String... args) {
        try {
            Long result = redis.execute(SCRIPT, keys, (Object[]) args);
            if (result == null) throw unavailable();
            return result;
        } catch (DataAccessException failure) {
            // Never fall back to process memory or retry an ambiguous mutation.
            throw unavailable();
        }
    }
    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("인증 정보가 올바르지 않거나 만료되었습니다. 다시 인증해주세요.");
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "인증 저장소를 사용할 수 없습니다. 잠시 후 다시 인증해주세요.");
    }
}
