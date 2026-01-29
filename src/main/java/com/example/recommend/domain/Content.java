package com.example.recommend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "contents")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Content {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // MOVIE / TV
    @Column(nullable = false, length = 10)
    private String type;

    @Column(nullable = false)
    private String title;

    @Column(length = 2000)
    private String overview;

    @Column(length = 200)
    private String genres;

    private LocalDate releaseDate;

    // 포스터/배경
    private String posterUrl;
    private String backdropUrl;

    // 지표(추천/정렬에 사용)
    private Double rating;        // 평균 평점
    private Long ratingCount;     // 평점 수
    private Long viewCount;       // 조회수(트렌딩)
}

