package com.example.recommend.chat.client;

/** Only server-loaded history and the validated current question may populate this record. */
public record AiMessage(Role role, String text, java.util.List<Long> contentIds) {
    public AiMessage(Role role, String text) { this(role, text, java.util.List.of()); }
    public AiMessage { contentIds = java.util.List.copyOf(contentIds); }
    public enum Role { USER, ASSISTANT }
}
