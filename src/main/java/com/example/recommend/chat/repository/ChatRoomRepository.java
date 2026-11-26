package com.example.recommend.chat.repository;

import com.example.recommend.chat.domain.ChatRoom;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    Optional<ChatRoom> findByIdAndUserId(Long id, Long userId);
    List<ChatRoom> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long before, Pageable page);
    long countByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChatRoom r where r.id = :id and r.user.id = :userId")
    Optional<ChatRoom> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
