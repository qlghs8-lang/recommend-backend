package com.example.recommend.admin.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class AdminContentRequest {

    private String type;       // MOVIE / TV
    private String title;
    private String overview;
    private String genres;     // CSV
    private LocalDate releaseDate;

    private String posterUrl;
    private String backdropUrl;

    private Double rating;
    private Long ratingCount;
    private Long viewCount;
}
