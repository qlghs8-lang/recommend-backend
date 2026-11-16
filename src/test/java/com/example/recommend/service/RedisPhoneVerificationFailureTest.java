package com.example.recommend.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisPhoneVerificationFailureTest {
    @Test void unavailableStoreFailsClosedWithoutSendingOrRetrying() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SmsSender sender = mock(SmsSender.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        var service = new PhoneVerificationService(new RedisPhoneVerificationStore(redis, sender, "test"));
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> service.request("01012345678", "owner", "ip")).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> service.verify("id", "01012345678", "123456", "owner")).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> service.consume("ticket", "01012345678", "owner")).getStatusCode().value());
        verifyNoInteractions(sender);
        verify(redis, times(3)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
