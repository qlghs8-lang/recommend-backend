package com.example.recommend.config;

import com.example.recommend.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class PhoneVerificationConfig {
    @Bean
    PhoneVerificationStore phoneVerificationStore(SmsSender sender, ObjectProvider<StringRedisTemplate> redis,
            @Value("${app.phone.store:memory}") String store,
            @Value("${app.phone.redis-prefix:recommend:phone:v1}") String prefix) {
        return switch (store) {
            case "memory" -> new InMemoryPhoneVerificationStore(sender);
            case "redis" -> new RedisPhoneVerificationStore(redis.getObject(), sender, prefix);
            default -> throw new IllegalArgumentException("app.phone.store must be memory or redis");
        };
    }
}
