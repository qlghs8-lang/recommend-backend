package com.example.recommend.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.springframework.http.MediaType TEXT_UTF8 =
            new org.springframework.http.MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8);

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<String> handleConflict(ConflictException e) {
        return ResponseEntity.status(409).contentType(TEXT_UTF8).body(e.getMessage());
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<String> handleIntegrityConflict() {
        return ResponseEntity.status(409).contentType(TEXT_UTF8).body("기존 데이터와 충돌하는 요청입니다.");
    }

    // ✅ 휴대폰 인증 필요 같은 "정책 위반" -> 403
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<String> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(403).contentType(TEXT_UTF8).body(e.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<String> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(404).contentType(TEXT_UTF8).body(e.getMessage());
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<String> handleStatus(org.springframework.web.server.ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).contentType(TEXT_UTF8).body(e.getReason());
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleUploadSize() {
        return ResponseEntity.status(413).contentType(TEXT_UTF8).body("이미지는 5MB 이하여야 합니다.");
    }

    // 잘못된 입력/검증 실패 -> 400
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().contentType(TEXT_UTF8).body(e.getMessage());
    }
}
