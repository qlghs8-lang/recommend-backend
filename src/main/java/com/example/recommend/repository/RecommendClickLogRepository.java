package com.example.recommend.repository;

import com.example.recommend.domain.RecommendClickLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RecommendClickLogRepository extends JpaRepository<RecommendClickLog, Long> {

    boolean existsByRecommendLogId(Long recommendLogId);

    boolean existsByRecommendLogIdAndUserId(Long recommendLogId, Long userId);

    long countByRecommendLogIdIn(Collection<Long> recommendLogIds);

    List<RecommendClickLog> findByRecommendLogIdIn(Collection<Long> recommendLogIds);

    // ✅ NEW: 유저의 최근 클릭한 contentId (최신순)
    @Query("""
        select rcl.contentId
        from RecommendClickLog rcl
        where rcl.userId = :userId
        order by rcl.clickedAt desc
    """)
    List<Long> findRecentClickedContentIds(@Param("userId") Long userId, Pageable pageable);
}
