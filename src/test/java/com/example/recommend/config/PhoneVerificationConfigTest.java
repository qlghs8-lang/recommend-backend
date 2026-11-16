package com.example.recommend.config;

import com.example.recommend.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhoneVerificationConfigTest {
    @Test void memoryDefaultDoesNotRequireRedis() {
        try (var context = context(Map.of(), false)) {
            context.refresh();
            assertInstanceOf(InMemoryPhoneVerificationStore.class, context.getBean(PhoneVerificationStore.class));
            assertNotNull(context.getBean(PhoneVerificationService.class));
        }
    }
    @Test void redisSelectionInjectsSharedStore() {
        try (var context = context(Map.of("app.phone.store", "redis"), true)) {
            context.refresh();
            assertInstanceOf(RedisPhoneVerificationStore.class, context.getBean(PhoneVerificationStore.class));
        }
    }
    @Test void invalidModeOrMissingRedisCannotSilentlySelectMemory() {
        for (String mode : new String[]{"typo", "redis"}) {
            try (var context = context(Map.of("app.phone.store", mode), false)) {
                assertThrows(org.springframework.beans.BeansException.class, context::refresh);
            }
        }
    }
    private AnnotationConfigApplicationContext context(Map<String, Object> properties, boolean redis) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(PhoneVerificationConfig.class, SmsConfig.class, PhoneVerificationService.class);
        if (redis) context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
        return context;
    }
}
