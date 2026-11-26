package com.example.recommend.chat.service;

public class ChatException extends RuntimeException {
    private final int status;
    private final String code;

    private ChatException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
    public static ChatException notFound() {
        return new ChatException(404, "CHAT_ROOM_NOT_FOUND", "대화방을 찾을 수 없습니다.");
    }
    public static ChatException conflict() {
        return new ChatException(409, "CHAT_CHANGED", "대화가 변경되었습니다. 기록을 다시 불러와 주세요.");
    }
    public static ChatException limitReached() {
        return new ChatException(409, "CHAT_LIMIT", "대화 저장 한도에 도달했습니다. 새 대화를 시작하거나 이전 대화방을 삭제해 주세요.");
    }
    public static ChatException unavailable() {
        return new ChatException(503, "AI_UNAVAILABLE", "AI 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
    public static ChatException busy() {
        return new ChatException(429, "AI_BUSY", "AI 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
    }
    public static ChatException timeout() {
        return new ChatException(504, "AI_TIMEOUT", "AI 응답 시간이 초과되었습니다.");
    }
    public static ChatException invalidResponse() {
        return new ChatException(502, "AI_INVALID_RESPONSE", "AI의 완전한 응답을 받지 못했습니다.");
    }
}
