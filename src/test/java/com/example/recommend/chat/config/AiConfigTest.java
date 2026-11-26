package com.example.recommend.chat.config;

import com.example.recommend.chat.client.AiClient;
import com.example.recommend.chat.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AiConfigTest {
    @Test void defaultDisabledContextStartsWithoutCredentialsOrNetwork() {
        try (var context = context(Map.of())) {
            context.refresh();
            assertNotNull(context.getBean(ChatService.class));
            assertEquals(503, assertThrows(ChatException.class,
                    () -> context.getBean(AiClient.class).reply("hello")).status());
        }
    }

    @Test void enabledContextBuildsSdkWithoutNetworkAndClosesIt() {
        try (var context = context(Map.of("app.ai.enabled", "true", "app.ai.api-key", "synthetic-test-key",
                "app.ai.model", "test-model"))) {
            context.refresh();
            assertNotNull(context.getBean(AiClient.class));
        }
    }

    @Test void enabledAiFailsFastWithSanitizedMissingConfigurationError() {
        for (Map<String, Object> values : java.util.List.<Map<String, Object>>of(
                Map.of("app.ai.enabled", "true"),
                Map.of("app.ai.enabled", "true", "app.ai.api-key", "synthetic-private-value"),
                Map.of("app.ai.enabled", "true", "app.ai.model", "test-model"))) {
            try (var context = context(values)) {
                var error = assertThrows(org.springframework.beans.BeansException.class, context::refresh);
                assertFalse(error.toString().contains("synthetic-private-value"));
            }
        }
    }

    @Test void invalidLimitsAreRejected() {
        for (String key : new String[]{"max-input-chars", "max-output-tokens", "timeout-seconds",
                "max-concurrent", "requests-per-minute", "global-requests-per-minute"}) {
            try (var context = context(Map.of("app.ai." + key, "0"))) {
                assertThrows(org.springframework.beans.BeansException.class, context::refresh);
            }
        }
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> values) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", values));
        context.register(AiConfig.class, ChatService.class, ChatRequestLimiter.class);
        return context;
    }
}
