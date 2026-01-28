package com.example.recommend.admin.dto;

import com.example.recommend.domain.Content;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class AdminContentResponse {

    private Long id;
    private String type;
    private String title;
    private String overview;
    private String genres;
    private LocalDate releaseDate;

    private String posterUrl;
    private String backdropUrl;

    private Double rating;
    private Long ratingCount;
    private Long viewCount;

    public static AdminContentResponse from(Content c) {
        return new AdminContentResponse(
                c.getId(),
                c.getType(),
                c.getTitle(),
                c.getOverview(),
                c.getGenres(),
                c.getReleaseDate(),
                c.getPosterUrl(),
                c.getBackdropUrl(),
                c.getRating(),
                c.getRatingCount(),
                c.getViewCount()
        );
    }
}
