package com.example.recommend.repository;

import com.example.recommend.admin.dto.AdminRecommendLogRow;
import com.example.recommend.admin.dto.AdminSourceStatRow;
import com.example.recommend.admin.dto.AdminSourceStatsRow;
import com.example.recommend.domain.RecommendLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RecommendLogRepository
        extends org.springframework.data.jpa.repository.JpaRepository<RecommendLog, Long> {

    @Query("""
        select rl.id from RecommendLog rl
        where rl.createdAt < :cutoff and rl.exposedAt is null
          and not exists (select 1 from RecommendClickLog c where c.recommendLogId = rl.id)
        order by rl.id
        """)
    List<Long> findCleanupCandidates(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    // Recheck eligibility in the write statement, after acquiring the tracking row lock.
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        delete from RecommendLog rl where rl.id = :id
          and rl.createdAt < :cutoff and rl.exposedAt is null
          and not exists (select 1 from RecommendClickLog c where c.recommendLogId = rl.id)
        """)
    int deleteCleanupCandidate(@Param("id") Long id, @Param("cutoff") LocalDateTime cutoff);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select rl from RecommendLog rl where rl.id = :id")
    java.util.Optional<RecommendLog> findLockedById(@Param("id") Long id);

    // =========================================================
    // Admin: 추천 로그 테이블 (필터 + clicked 포함)
    // =========================================================
    @Query(
            value = """
                select new com.example.recommend.admin.dto.AdminRecommendLogRow(
                    rl.id,
                    rl.userId,
                    rl.contentId,
                    c.title,
                    rl.source,
                    rl.reason,
                    rl.exposedAt,
                    case when exists (
                        select 1 from RecommendClickLog rcl
                        where rcl.recommendLogId = rl.id
                    ) then true else false end
                )
                from RecommendLog rl
                left join Content c on c.id = rl.contentId
                where
                    rl.exposedAt is not null and (:from is null or rl.exposedAt >= :from)
                    and (
                        :source is null
                        or trim(:source) = ''
                        or lower(rl.source) like lower(concat('%', trim(:source), '%'))
                    )
                    and (:userId is null or rl.userId = :userId)
                    and (:contentId is null or rl.contentId = :contentId)
                    and (
                        :clicked is null
                        or (:clicked = true and exists (
                            select 1 from RecommendClickLog rcl2
                            where rcl2.recommendLogId = rl.id
                        ))
                        or (:clicked = false and not exists (
                            select 1 from RecommendClickLog rcl3
                            where rcl3.recommendLogId = rl.id
                        ))
                    )
                order by rl.exposedAt desc
            """,
            countQuery = """
                select count(distinct rl.id)
                from RecommendLog rl
                where
                    rl.exposedAt is not null and (:from is null or rl.exposedAt >= :from)
                    and (
                        :source is null
                        or trim(:source) = ''
                        or lower(rl.source) like lower(concat('%', trim(:source), '%'))
                    )
                    and (:userId is null or rl.userId = :userId)
                    and (:contentId is null or rl.contentId = :contentId)
                    and (
                        :clicked is null
                        or (:clicked = true and exists (
                            select 1 from RecommendClickLog rcl2
                            where rcl2.recommendLogId = rl.id
                        ))
                        or (:clicked = false and not exists (
                            select 1 from RecommendClickLog rcl3
                            where rcl3.recommendLogId = rl.id
                        ))
                    )
            """
    )
    Page<AdminRecommendLogRow> findAdminLogRowsWithFilters(
            Pageable pageable,
            @Param("from") LocalDateTime from,
            @Param("source") String source,
            @Param("clicked") Boolean clicked,
            @Param("userId") Long userId,
            @Param("contentId") Long contentId
    );

    // =========================
    // Dashboard: impressions
    // =========================
    long countByExposedAtGreaterThanEqual(LocalDateTime from);

    // =========================
    // Dashboard: clicks (distinct recommendLogId 기준)
    // =========================
    @Query("""
        select count(distinct rcl.recommendLogId)
        from RecommendClickLog rcl
        join RecommendLog rl on rl.id = rcl.recommendLogId
        where rl.exposedAt >= :from
    """)
    long countDistinctClicksSince(@Param("from") LocalDateTime from);

    // =========================
    // Dashboard: impressions by source (레거시/대시보드용 유지)
    // =========================
    @Query("""
        select new com.example.recommend.admin.dto.AdminSourceStatRow(
            rl.source,
            count(distinct rl.id)
        )
        from RecommendLog rl
        where rl.exposedAt >= :from
        group by rl.source
    """)
    List<AdminSourceStatRow> countImpressionsBySourceSince(@Param("from") LocalDateTime from);

    // =========================
    // Dashboard: clicks by source (distinct recommendLogId 기준) (레거시/대시보드용 유지)
    // =========================
    @Query("""
        select new com.example.recommend.admin.dto.AdminSourceStatRow(
            rl.source,
            count(distinct rcl.recommendLogId)
        )
        from RecommendClickLog rcl
        join RecommendLog rl
            on rl.id = rcl.recommendLogId
        where rl.exposedAt >= :from
        group by rl.source
    """)
    List<AdminSourceStatRow> countClicksBySourceSince(@Param("from") LocalDateTime from);

    // =========================
    // ✅ total clicks by source (중복 클릭 포함) (레거시/대시보드용 유지)
    // =========================
    @Query("""
        select new com.example.recommend.admin.dto.AdminSourceStatRow(
            rl.source,
            count(rcl.id)
        )
        from RecommendClickLog rcl
        join RecommendLog rl
            on rl.id = rcl.recommendLogId
        where rl.exposedAt >= :from
        group by rl.source
    """)
    List<AdminSourceStatRow> countTotalClicksBySourceSince(@Param("from") LocalDateTime from);

    // =========================
    // ✅ total clicks (중복 포함)
    // =========================
    @Query("""
        select count(rcl.id)
        from RecommendClickLog rcl
        join RecommendLog rl on rl.id = rcl.recommendLogId
        where rl.exposedAt >= :from
    """)
    long countTotalClicksSince(@Param("from") LocalDateTime from);

    // =========================
    // ✅ by-source "진짜 쓸모있는" 1쿼리 버전
    // - Cohort: impressions exposed since from, with all clicks on those impressions.
    // - Repeated clicks affect totalClicks only, not impressions or uniqueClicks.
    // =========================
    @Query("""
        select new com.example.recommend.admin.dto.AdminSourceStatsRow(
            rl.source,
            count(distinct rl.id),
            count(distinct rcl.recommendLogId),
            count(rcl.id),
            case when count(distinct rl.id) = 0 then 0.0 else (count(distinct rcl.recommendLogId) * 1.0 / count(distinct rl.id)) end,
            case when count(distinct rl.id) = 0 then 0.0 else (count(rcl.id) * 1.0 / count(distinct rl.id)) end
        )
        from RecommendLog rl
        left join RecommendClickLog rcl
            on rcl.recommendLogId = rl.id
        where rl.exposedAt >= :from
        group by rl.source
    """)
    List<AdminSourceStatsRow> findSourceStatsSince(@Param("from") LocalDateTime from);
}
