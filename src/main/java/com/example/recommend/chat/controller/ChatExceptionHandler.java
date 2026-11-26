package com.example.recommend.chat.controller;

import com.example.recommend.chat.service.ChatException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice(assignableTypes = {ChatController.class, ConversationController.class})
public class ChatExceptionHandler {
    public record Error(String code, String message) { }

    @ExceptionHandler(ChatException.class)
    ResponseEntity<Error> handleAi(ChatException e) {
        var builder = ResponseEntity.status(e.status());
        if (e.status() == 429) builder.header("Retry-After", "60");
        return builder.body(new Error(e.code(), e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Error> handleInput() {
        return ResponseEntity.badRequest().body(new Error("INVALID_CHAT_REQUEST", "메시지 형식 또는 길이를 확인해 주세요."));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<Error> handleStorage() {
        // A schema/constraint failure is not evidence that another chat request won.
        return ResponseEntity.internalServerError().body(new Error("CHAT_STORAGE_ERROR",
                "대화를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요."));
    }
}
