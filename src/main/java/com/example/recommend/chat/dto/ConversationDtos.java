package com.example.recommend.chat.dto;

import com.example.recommend.chat.domain.*;
import java.time.Instant;
import java.util.List;

public final class ConversationDtos {
    private ConversationDtos() { }
    public record Room(Long id, String title, Instant createdAt, Instant updatedAt, int messageCount) {
        public static Room from(ChatRoom r) {
            return new Room(r.getId(), r.getTitle(), r.getCreatedAt(), r.getUpdatedAt(), r.getMessageCount());
        }
    }
    public record ContentLink(Long id, String title, String type, String posterUrl) { }
    public record Message(Long id, String role, String text, Instant createdAt, List<ContentLink> contents) {
        public Message { contents = List.copyOf(contents); }
        public static Message from(ChatMessage m, List<ContentLink> contents) {
            return new Message(m.getId(), m.getRole().name().toLowerCase(java.util.Locale.ROOT),
                    m.getText(), m.getCreatedAt(), contents);
        }
    }
    public record Rooms(List<Room> rooms, boolean hasMore, Long nextBefore) { }
    public record History(Room room, List<Message> messages, boolean hasMore, Long nextBefore) { }
    public record Turn(Room room, List<Message> messages, ChatResponse.Usage usage, boolean catalogGrounded) { }
}
