package com.example.recommend.chat.repository;

import com.example.recommend.chat.domain.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByRoomIdAndIdLessThanOrderByIdDesc(Long roomId, Long before, Pageable page);
    @Modifying
    @Query("delete from ChatMessage m where m.room.id = :roomId")
    void deleteForRoom(@Param("roomId") Long roomId);
}
