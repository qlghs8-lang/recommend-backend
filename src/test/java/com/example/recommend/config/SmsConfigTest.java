package com.example.recommend.config;
import com.example.recommend.controller.PublicPhoneController;
import com.example.recommend.exception.GlobalExceptionHandler;
import com.example.recommend.service.PhoneVerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class SmsConfigTest {
    @Test void noProviderReturns503AndNoCodeOrChallenge() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new PublicPhoneController(
            new PhoneVerificationService(new SmsConfig().unavailableSmsSender())))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/public/phone/request").contentType("application/json")
            .content("{\"phone\":\"01012345678\",\"requesterToken\":\"" + "a".repeat(64) + "\"}"))
            .andExpect(status().isServiceUnavailable());
    }
}
