package com.example.recommend.chat.config;

import com.example.recommend.chat.client.AiClient;
import com.example.recommend.chat.client.OpenAiClient;
import com.example.recommend.chat.service.ChatException;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.LogLevel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {
    @Bean(destroyMethod = "close")
    AiClient aiClient(AiProperties properties) {
        properties.validate();
        if (!properties.isEnabled()) {
            return message -> { throw ChatException.unavailable(); };
        }
        // Do not use fromEnv(): URLs and logging must stay under server control.
        return new OpenAiClient(createSdk(properties, "https://api.openai.com/v1"), properties);
    }

    static com.openai.client.OpenAIClient createSdk(AiProperties properties, String baseUrl) {
        var deadline = Duration.ofSeconds(properties.getTimeoutSeconds());
        return OpenAIOkHttpClient.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(baseUrl)
                .maxRetries(0)
                .timeout(com.openai.core.Timeout.builder().connect(deadline).read(deadline)
                        .write(deadline).request(deadline).build())
                .logLevel(LogLevel.OFF)
                .build();
    }
}
