package com.example.recommend.repository;

import com.example.recommend.domain.InteractionType;
import com.example.recommend.domain.UserContentInteraction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserContentInteractionRepository extends JpaRepository<UserContentInteraction, Long> {

	Optional<UserContentInteraction> findByUser_IdAndContent_IdAndEventType(
		    Long userId, Long contentId, InteractionType eventType
		);

    @Modifying
    @Query("""
        delete from UserContentInteraction u
        where u.user.id = :userId and u.content.id = :contentId and u.eventType = :eventType
    """)
    int deleteByUserIdAndContentIdAndEventType(@Param("userId") Long userId,
                                               @Param("contentId") Long contentId,
                                               @Param("eventType") InteractionType eventType);

    @Query("""
        select u.eventType from UserContentInteraction u
        where u.user.id = :userId and u.content.id = :contentId and u.eventType in :types
    """)
    List<InteractionType> findTypesForContent(@Param("userId") Long userId,
                                              @Param("contentId") Long contentId,
                                              @Param("types") List<InteractionType> types);

    // =========================
    // ✅ For You 추천용 메서드
    // =========================

    @Query("""
        select distinct u.content.id
        from UserContentInteraction u
        where u.user.id = :userId and u.eventType in :types
    """)
    List<Long> findContentIdsByUserAndTypes(@Param("userId") Long userId,
                                           @Param("types") List<InteractionType> types);

    @Query("""
        select distinct u.user.id
        from UserContentInteraction u
        where u.content.id in :contentIds
          and u.eventType in :types
          and u.user.id <> :userId
    """)
    List<Long> findOtherUserIdsWhoInteracted(@Param("userId") Long userId,
                                             @Param("contentIds") Collection<Long> contentIds,
                                             @Param("types") List<InteractionType> types);

    @Query("""
        select distinct u.content.id
        from UserContentInteraction u
        where u.user.id in :userIds
          and u.eventType in :types
    """)
    List<Long> findContentIdsByUserIdsAndTypes(@Param("userIds") Collection<Long> userIds,
                                               @Param("types") List<InteractionType> types);

    // =========================
    // ✅ NEW: 최근 좋아요/찜 콘텐츠 id (최신순)
    // =========================
    @Query("""
        select u.content.id
        from UserContentInteraction u
        where u.user.id = :userId
          and u.eventType in :types
        order by u.createdAt desc
    """)
    List<Long> findRecentContentIdsByUserAndTypes(@Param("userId") Long userId,
                                                  @Param("types") List<InteractionType> types,
                                                  Pageable pageable);
}
