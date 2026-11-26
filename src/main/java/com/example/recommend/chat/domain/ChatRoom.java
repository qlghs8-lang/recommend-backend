package com.example.recommend.chat.domain;

import com.example.recommend.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "chat_rooms", indexes = @Index(name = "idx_chat_room_owner", columnList = "user_id,id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ChatRoom {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false, length = 80)
    private String title = "새 대화";
    @Column(nullable = false)
    private Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    @Column(nullable = false)
    private Instant updatedAt = createdAt;
    @Column(nullable = false)
    private int messageCount;

    public ChatRoom(User user) { this.user = user; }

    public void recordTurn(String question) {
        if (messageCount == 0) title = question.substring(0, question.offsetByCodePoints(0,
                Math.min(40, question.codePointCount(0, question.length()))));
        messageCount += 2;
        updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
