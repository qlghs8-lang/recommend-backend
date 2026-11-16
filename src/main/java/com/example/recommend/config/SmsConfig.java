package com.example.recommend.config;

import com.example.recommend.service.SmsSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Configuration
public class SmsConfig {
    @Bean
    @ConditionalOnMissingBean(SmsSender.class)
    SmsSender unavailableSmsSender() {
        return (phone, code) -> {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "문자 발송 서비스가 설정되지 않았습니다. 잠시 후 다시 시도해주세요.");
        };
    }
}
