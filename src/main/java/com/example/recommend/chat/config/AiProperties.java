package com.example.recommend.chat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("app.ai")
public class AiProperties {
    private boolean enabled;
    private String apiKey = "";
    // Explicit operator selection avoids silently changing model cost or availability.
    private String model = "";
    private int maxInputChars = 2000;
    private int maxOutputTokens = 800;
    private int timeoutSeconds = 20;
    private int maxConcurrent = 4;
    private int requestsPerMinute = 6;
    private int globalRequestsPerMinute = 60;

    public void validate() {
        if (maxInputChars < 1 || maxInputChars > 8000
                || maxOutputTokens < 16 || maxOutputTokens > 4096
                || timeoutSeconds < 1 || timeoutSeconds > 60
                || maxConcurrent < 1 || maxConcurrent > 16
                || requestsPerMinute < 1 || requestsPerMinute > 60
                || globalRequestsPerMinute < 1 || globalRequestsPerMinute > 600) {
            throw new IllegalArgumentException("AI limits are outside the supported range");
        }
        if (enabled && (apiKey == null || apiKey.isBlank() || model == null
                || !model.matches("[a-zA-Z0-9._:-]{1,100}"))) {
            throw new IllegalArgumentException("Enabled AI requires OPENAI_API_KEY and a valid AI_MODEL");
        }
    }
}
