package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.domain.Content;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Exact, bounded public lookup. Catalog text is rendered as data and never sent for generation. */
@Service
@RequiredArgsConstructor
public class CatalogDetails {
    private final EntityManager em;

    @Transactional(readOnly = true)
    public ChatResponse reply(ChatIntent intent, String question, TurnBudget budget) {
        if (intent.route() != ChatIntent.Route.DETAIL || intent.referenceTurn() != null) throw ChatException.invalidResponse();
        // A well-formed model locator still needs evidence in this turn, not model memory or hidden history.
        if (intent.title() != null && !question.toLowerCase(Locale.ROOT).contains(intent.title().toLowerCase(Locale.ROOT)))
            throw ChatException.invalidResponse();
        if (intent.contentId() != null && !Pattern.compile("(?iu)\\bID\\s*[:#]?\\s*" + intent.contentId() + "(?!\\d)")
                .matcher(question).find()) throw ChatException.invalidResponse();
        if (intent.releaseYear() != null && !Pattern.compile("(?<!\\d)" + intent.releaseYear() + "(?!\\d)")
                .matcher(question).find()) throw ChatException.invalidResponse();
        budget.remainingTime();
        if (intent.title() != null && !TitleBoundary.permits(question, intent.title())) return answer(TitleBoundary.QUESTION, false);
        var cb = em.getCriteriaBuilder();
        var query = cb.createQuery(Content.class);
        var c = query.from(Content.class);
        var filters = new ArrayList<Predicate>();
        if (intent.contentId() != null) filters.add(cb.equal(c.get("id"), intent.contentId()));
        else filters.add(cb.equal(cb.lower(c.get("title")), intent.title().toLowerCase(Locale.ROOT)));
        if (intent.media() != ChatIntent.Media.ANY) filters.add(cb.equal(cb.upper(c.get("type")), intent.media().name()));
        if (intent.releaseYear() != null) {
            filters.add(cb.greaterThanOrEqualTo(c.get("releaseDate"), LocalDate.of(intent.releaseYear(), 1, 1)));
            filters.add(cb.lessThan(c.get("releaseDate"), LocalDate.of(intent.releaseYear() + 1, 1, 1)));
        }
        // Preserve full detail fields while bypassing a managed copy from an earlier
        // branch of the same OSIV request (including a compound's final recheck).
        query.select(cb.construct(Content.class, c.get("id"), c.get("type"), c.get("title"), c.get("overview"),
                c.get("genres"), c.get("releaseDate"), c.get("runtimeMinutes"), c.get("episodeRuntimeMinutes"),
                c.get("posterUrl"), c.get("backdropUrl"), c.get("rating"), c.get("ratingCount"), c.get("viewCount")))
                .where(filters.toArray(Predicate[]::new)).orderBy(cb.asc(c.get("id")));
        var matches = em.createQuery(query).setMaxResults(6).setHint("jakarta.persistence.query.timeout", 2000)
                .setHint("org.hibernate.cacheable", false).getResultList();
        budget.remainingTime();
        if (matches.isEmpty()) return answer("지정한 제목 또는 ID와 조건에 일치하는 작품을 현재 카탈로그에서 찾지 못했어요. "
                + "정확한 등록 제목이나 카탈로그 ID를 알려 주세요. 카탈로그 밖의 줄거리나 상세 정보는 확인하지 않았어요.", false);
        if (matches.size() > 1) {
            var text = new StringBuilder("같은 제목의 작품이 여러 개예요. 원하는 작품의 ID를 명시해서 다시 질문해 주세요.");
            matches.stream().limit(5).forEach(work -> text.append("\n").append(label(work)));
            if (matches.size() > 5) text.append("\n후보가 더 있어요. 영화/시리즈 또는 공개 연도로 범위를 좁혀 주세요.");
            return answer(text.toString(), true, matches.stream().limit(5).map(Content::getId).toList());
        }
        var work = matches.get(0);
        return answer("현재 카탈로그에 저장된 작품 정보예요.\n\n" + label(work)
                + "\n장르: " + field(work.getGenres())
                + "\n" + duration(work)
                + "\n카탈로그 줄거리: " + field(work.getOverview())
                + "\n\n출연진·감독·결말 해설·시청 가능 서비스는 이 카탈로그 정보만으로 확인할 수 없어요.", true, List.of(work.getId()));
    }

    private static String label(Content work) {
        String type = "MOVIE".equalsIgnoreCase(work.getType()) ? "영화"
                : "TV".equalsIgnoreCase(work.getType()) ? "시리즈" : "유형 정보 없음";
        return work.getTitle() + " (ID: " + work.getId() + ") · " + type
                + " · 공개일: " + (work.getReleaseDate() == null ? "정보 없음" : work.getReleaseDate());
    }
    private static String field(String value) { return value == null || value.isBlank() ? "정보 없음" : value; }
    private static String duration(Content work) {
        var candidate = CatalogCandidate.from(work);
        Integer minutes = "MOVIE".equalsIgnoreCase(work.getType()) ? candidate.runtimeMinutes() : candidate.episodeRuntimeMinutes();
        return ("MOVIE".equalsIgnoreCase(work.getType()) ? "러닝타임: " : "회당 러닝타임(대표값, 회차별 차이 가능): ")
                + (minutes == null ? "정보 없음" : minutes + "분");
    }
    private static ChatResponse answer(String text, boolean grounded) {
        return new ChatResponse(text, new ChatResponse.Usage(0, 0, 0), grounded);
    }
    private static ChatResponse answer(String text, boolean grounded, List<Long> ids) {
        return new ChatResponse(text, new ChatResponse.Usage(0, 0, 0), grounded, ids);
    }
}
