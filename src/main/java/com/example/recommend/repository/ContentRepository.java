package com.example.recommend.repository;

import com.example.recommend.domain.Content;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ContentRepository extends JpaRepository<Content, Long> {

    List<Content> findByOrderByViewCountDesc(Pageable pageable);

    List<Content> findByOrderByReleaseDateDesc(Pageable pageable);

    List<Content> findByOrderByRatingDesc(Pageable pageable);

    // 추천 결과 후보를 id로 가져오기용
    List<Content> findByIdIn(Collection<Long> ids);

    @Query("""
        select c
        from Content c
        where
            (:q is null or trim(:q) = '' or
                lower(c.title) like lower(concat('%', trim(:q), '%')) or
                lower(coalesce(c.overview, '')) like lower(concat('%', trim(:q), '%'))
            )
            and (:type is null or trim(:type) = '' or upper(c.type) = upper(trim(:type)))
            and (
                :genre is null or trim(:genre) = '' or
                lower(coalesce(c.genres, '')) like lower(concat('%', trim(:genre), '%'))
            )
    """)
    Page<Content> searchAdminContents(
            @Param("q") String q,
            @Param("type") String type,
            @Param("genre") String genre,
            Pageable pageable
    );

    @Query("""
        select c
        from Content c
        where
            (:q is null or trim(:q) = '' or
                lower(c.title) like lower(concat('%', trim(:q), '%')) or
                lower(coalesce(c.overview, '')) like lower(concat('%', trim(:q), '%'))
            )
            and (:type is null or trim(:type) = '' or upper(c.type) = upper(trim(:type)))
            and (
                :genre is null or trim(:genre) = '' or
                lower(coalesce(c.genres, '')) like lower(concat('%', trim(:genre), '%'))
            )
    """)
    Page<Content> searchContents(
            @Param("q") String q,
            @Param("type") String type,
            @Param("genre") String genre,
            Pageable pageable
    );

    @Query("""
        select c.genres
        from Content c
        where c.genres is not null and trim(c.genres) <> ''
    """)
    List<String> findAllGenresCsv();
}

