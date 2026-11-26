package com.example.recommend.chat.repository;

import com.example.recommend.domain.Content;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

/** Post-generation public snapshots; constructor results bypass managed entity identity reuse. */
public interface CatalogSnapshotRepository extends Repository<Content, Long> {
    @Query("""
        select new com.example.recommend.domain.Content(
            c.id, c.type, c.title, c.overview, c.genres, c.releaseDate,
            c.runtimeMinutes, c.episodeRuntimeMinutes, c.posterUrl, c.backdropUrl,
            c.rating, c.ratingCount, c.viewCount)
        from Content c where c.id in :ids
        """)
    @QueryHints({@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"),
            @QueryHint(name = "org.hibernate.cacheable", value = "false")})
    List<Content> findByIdIn(@Param("ids") Collection<Long> ids);
}
