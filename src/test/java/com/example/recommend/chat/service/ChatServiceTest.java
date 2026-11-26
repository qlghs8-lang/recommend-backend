package com.example.recommend.chat.service;

import com.example.recommend.chat.client.AiClient;
import com.example.recommend.chat.config.AiProperties;
import com.example.recommend.chat.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatServiceTest {
    private final AiProperties properties = new AiProperties();
    private final AiClient client = mock(AiClient.class);
    private final AtomicLong time = new AtomicLong();
    private final ChatRequestLimiter limiter = new ChatRequestLimiter(properties, time::get);
    private final ChatService service = new ChatService(client, properties, limiter);

    @Test void rejectsEmptyAndOversizedMessagesBeforeCallingProvider() {
        properties.setEnabled(true);
        for (String message : new String[]{null, "", " \n\t", "x".repeat(2001)}) {
            assertThrows(IllegalArgumentException.class, () -> service.reply(1L, message));
        }
        verifyNoInteractions(client);
    }

    @Test void disabledAiNeverCallsProvider() {
        assertEquals(503, assertThrows(ChatException.class, () -> service.reply(1, "hello")).status());
        verifyNoInteractions(client);
    }

    @Test void acceptsBoundaryInputAndTrimsWhitespace() {
        properties.setEnabled(true);
        var expected = new ChatResponse("hello", new ChatResponse.Usage(1, 1, 2), false);
        when(client.reply("x".repeat(2000))).thenReturn(expected);
        assertSame(expected, service.reply(1, "x".repeat(2000)));
        service.reply(1, " hello ");
        verify(client).reply("hello");
    }

    @Test void rateLimitIsPerUserAndExpiresAfterOneMinute() {
        properties.setEnabled(true);
        properties.setRequestsPerMinute(1);
        service.reply(1, "hello");
        assertEquals(429, assertThrows(ChatException.class, () -> service.reply(1, "hello")).status());
        service.reply(2, "hello");
        time.set(java.time.Duration.ofMinutes(1).toNanos());
        service.reply(1, "hello");
        verify(client, times(3)).reply("hello");
    }

    @Test void failedRequestsConsumeQuotaButReleaseConcurrency() {
        properties.setEnabled(true);
        properties.setMaxConcurrent(1);
        properties.setRequestsPerMinute(1);
        when(client.reply(anyString())).thenThrow(ChatException.timeout());
        assertEquals(504, assertThrows(ChatException.class, () -> service.reply(1, "hello")).status());
        assertEquals(429, assertThrows(ChatException.class, () -> service.reply(1, "hello")).status());
        assertEquals(504, assertThrows(ChatException.class, () -> service.reply(2, "hello")).status());
    }

    @Test void globalQuotaBoundsWorkAcrossDifferentUsers() {
        properties.setEnabled(true);
        properties.setGlobalRequestsPerMinute(2);
        service.reply(1, "hello");
        service.reply(2, "hello");
        assertEquals(429, assertThrows(ChatException.class, () -> service.reply(3, "hello")).status());
        time.set(java.time.Duration.ofMinutes(1).toNanos());
        service.reply(3, "hello");
    }

    @Test void simultaneousRequestsRespectConcurrencyLimit() throws Exception {
        properties.setEnabled(true);
        properties.setMaxConcurrent(1);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(client.reply(anyString())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            return null;
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.reply(1, "hello"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(429, assertThrows(ChatException.class, () -> service.reply(2, "hello")).status());
            finish.countDown();
            first.get(5, TimeUnit.SECONDS);
            service.reply(2, "hello");
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }
}
