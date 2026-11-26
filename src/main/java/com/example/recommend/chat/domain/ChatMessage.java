package com.example.recommend.chat.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "chat_messages", indexes = @Index(name = "idx_chat_message_room", columnList = "room_id,id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ChatMessage {
    public enum Role { USER, ASSISTANT }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Role role;
    // The default @Column length (255) makes MySQL choose TINYTEXT even with @Lob.
    @Lob @Column(nullable = false, length = org.hibernate.Length.LONG32)
    private String text;
    // Server-written ordered public catalog IDs. Never derive these from answer text or user input.
    @Column(name = "content_references", length = 128)
    private String contentReferences;
    @Lob @Column(name = "recommendation_provenance", length = org.hibernate.Length.LONG32)
    private String recommendationProvenance;
    @Column(nullable = false)
    private Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

    public ChatMessage(ChatRoom room, Role role, String text) {
        this.room = room;
        this.role = role;
        this.text = text;
    }

    public ChatMessage(ChatRoom room, String text, java.util.List<Long> ids) {
        this(room, Role.ASSISTANT, text);
        this.contentReferences = ids.isEmpty() ? null : ids.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    public ChatMessage(ChatRoom room, String text, java.util.List<Long> ids, RecommendationProvenance provenance) {
        this(room, text, ids);
        if (provenance != null) {
            if (!provenance.ids().equals(ids)) throw com.example.recommend.chat.service.ChatException.invalidResponse();
            recommendationProvenance = provenance.encode();
        }
    }

    public RecommendationProvenance provenance() {
        if (recommendationProvenance == null) return null; // Legacy rows are intentionally not reconstructed from text.
        var result = RecommendationProvenance.decode(recommendationProvenance);
        if (role != Role.ASSISTANT || !result.ids().equals(referencedIds()))
            throw com.example.recommend.chat.service.ChatException.invalidResponse();
        return result;
    }

    public java.util.List<Long> referencedIds() {
        if (role != Role.ASSISTANT || contentReferences == null) return java.util.List.of();
        try {
            var ids = java.util.Arrays.stream(contentReferences.split(",", -1)).map(Long::valueOf).toList();
            if (ids.size() > 5 || ids.stream().anyMatch(id -> id <= 0)
                    || new java.util.HashSet<>(ids).size() != ids.size()) return java.util.List.of();
            return ids;
        } catch (NumberFormatException e) { return java.util.List.of(); }
    }
}
