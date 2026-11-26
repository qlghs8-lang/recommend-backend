package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.domain.Content;
import com.example.recommend.domain.InteractionType;
import com.example.recommend.domain.UserContentInteraction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogRetriever {
    public static final int MAX_CANDIDATES = 12;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> STOP_WORDS = Set.of("추천", "추천해", "추천해줘", "추천해주세요", "해줘", "해주세요",
            "좀", "좋은", "좋을", "재밌는", "재미있는", "볼만한", "볼", "만한", "보면", "보기", "보고", "싶어", "싶은", "작품", "영화", "드라마", "시리즈",
            "소개", "소개해", "소개해줘", "소개해주세요", "알려줘", "알려주세요", "다른거", "다른걸",
            "다른", "더", "그럼", "비슷한", "것", "걸", "뭐", "있어", "있나요", "추천좀", "movie", "movies", "tv",
            "recommend", "recommendation", "recommendations", "please", "a", "the", "some", "show", "shows", "series");
    private static final Map<String, List<String>> GENRES = Map.ofEntries(
            Map.entry("sf", List.of("sf", "science fiction", "sci-fi")),
            Map.entry("스릴러", List.of("스릴러", "thriller")),
            Map.entry("공포", List.of("공포", "horror")),
            Map.entry("코미디", List.of("코미디", "comedy")),
            Map.entry("액션", List.of("액션", "action")),
            Map.entry("로맨스", List.of("로맨스", "romance")),
            Map.entry("애니메이션", List.of("애니메이션", "animation")),
            Map.entry("판타지", List.of("판타지", "fantasy")),
            Map.entry("미스터리", List.of("미스터리", "mystery")),
            Map.entry("다큐멘터리", List.of("다큐멘터리", "documentary")));
    private final EntityManager em;
    private final ObjectProvider<ReviewedMetadataSearch> reviewedMetadata;

    public Optional<CatalogCandidate> detail(long id) {
        // Detached constructor results avoid OSIV reusing the entity read before generation.
        return em.createQuery("""
                select new com.example.recommend.domain.Content(
                    c.id, c.type, c.title, c.overview, c.genres, c.releaseDate,
                    c.runtimeMinutes, c.episodeRuntimeMinutes, c.posterUrl, c.backdropUrl,
                    c.rating, c.ratingCount, c.viewCount)
                from Content c where c.id = :id
                """, Content.class)
                .setParameter("id", id).setMaxResults(1).setHint("jakarta.persistence.query.timeout", 2000)
                .setHint("org.hibernate.cacheable", false)
                .getResultList().stream().findFirst().map(CatalogCandidate::from);
    }

    public List<CatalogCandidate> retrieve(List<AiMessage> context) {
        return retrieve(context, null, null, RecommendationCriteria.NONE, false);
    }

    public List<CatalogCandidate> retrieve(List<AiMessage> context, RecommendationCriteria criteria) {
        return retrieve(context, null, null, criteria, false);
    }

    public List<CatalogCandidate> refine(List<AiMessage> context, RecommendationCriteria criteria) {
        return retrieve(context, null, null, criteria, true);
    }

    public List<CatalogCandidate> retrievePersonalized(long userId, List<AiMessage> context, ChatTaste taste) {
        if (userId <= 0 || taste == null) throw ChatException.invalidResponse();
        // Explicit personal requests do not inherit unrelated earlier lexical search terms.
        String question = ChatTaste.searchText(context.get(context.size() - 1).text());
        return retrieve(List.of(new AiMessage(AiMessage.Role.USER, question)), userId, taste, RecommendationCriteria.NONE, false);
    }

    public List<CatalogCandidate> retrievePersonalized(long userId, List<AiMessage> context, ChatTaste taste, RecommendationCriteria criteria) {
        if (userId <= 0 || taste == null) throw ChatException.invalidResponse();
        return retrieve(context, userId, taste, criteria, false);
    }

    static String effectiveQuestion(List<AiMessage> context) {
        String current = context.get(context.size() - 1).text();
        // An elliptical follow-up may reuse only the most recent user question in this room.
        if (terms(current).isEmpty() && isFollowUp(current)) {
            for (int i = context.size() - 2; i >= 0; i--) {
                if (context.get(i).role() == AiMessage.Role.USER) {
                    return context.get(i).text();
                }
            }
        }
        return current;
    }

    public RecommendationCriteria withReviewedAliasExclusions(RecommendationCriteria criteria) {
        var names = criteria.constraints().stream().filter(c -> c.kind() == RecommendationConstraint.Kind.TITLE_EXCLUDE)
                .map(RecommendationConstraint::value).toList();
        if (names.isEmpty()) return criteria;
        var metadata = reviewedMetadata.getIfAvailable();
        if (metadata == null) return criteria;
        var excluded = new LinkedHashSet<>(criteria.excludedIds());
        metadata.exclusions(names).forEach(w -> excluded.add(w.id()));
        return excluded.equals(criteria.excludedIds()) ? criteria
                : new RecommendationCriteria(criteria.constraints(), criteria.onlyIds(), excluded, criteria.missingReference());
    }

    private List<CatalogCandidate> retrieve(List<AiMessage> context, Long userId, ChatTaste taste, RecommendationCriteria requestedCriteria, boolean refinement) {
        var criteria = withReviewedAliasExclusions(requestedCriteria);
        String current = effectiveQuestion(context);
        if (criteria.clarification() != null) return List.of();
        var terms = criteria.active() && !refinement ? new LinkedHashSet<>(criteria.rankingTerms()) : terms(current);
        String type = criteria.active() ? null : type(context.get(context.size() - 1).text());
        if (type == null && !criteria.active()) type = type(current);
        var cb = em.getCriteriaBuilder();
        var query = cb.createQuery(Content.class);
        var content = query.from(Content.class);
        var filters = new ArrayList<Predicate>();
        if (type != null) filters.add(cb.equal(cb.upper(content.get("type")), type));
        filters.addAll(criteria.predicates(cb, content));
        var matches = new ArrayList<Predicate>();
        jakarta.persistence.criteria.Expression<Integer> score = cb.literal(0);
        for (String term : terms) {
            var title = cb.like(cb.lower(content.get("title")), like(term), '\\');
            var genre = cb.like(cb.lower(content.get("genres")), like(term), '\\');
            var overview = cb.like(cb.lower(content.get("overview")), like(term), '\\');
            matches.add(cb.or(title, genre, overview));
            score = cb.sum(score, cb.<Integer>selectCase().when(title, 6).when(genre, 3).when(overview, 1).otherwise(0));
        }
        // Reviewed labels/whole-work aliases expand discovery, never hard filters or model evidence.
        var metadata = reviewedMetadata.getIfAvailable();
        var hints = metadata == null ? List.<ReviewedMetadataSearch.Hit>of() : metadata.search(current, terms);
        for (var hit : hints) {
            var work = hit.work();
            // Ignore stale links, including a reused ID or changed title/overview/type.
            var match = cb.and(cb.equal(content.get("id"), work.id()),
                    cb.equal(cb.function("hex", String.class, content.get("type")), cb.function("hex", String.class, cb.literal(work.type()))),
                    cb.equal(cb.function("hex", String.class, content.get("title")), cb.function("hex", String.class, cb.literal(work.title()))),
                    cb.equal(cb.function("hex", String.class, content.get("overview")), cb.function("hex", String.class, cb.literal(work.overview()))));
            matches.add(match);
            score = cb.sum(score, cb.<Integer>selectCase().when(match, hit.alias() ? 6 : 1).otherwise(0));
        }
        boolean scored = !terms.isEmpty() || !hints.isEmpty();
        if ((!criteria.active() || refinement) && !matches.isEmpty()) filters.add(cb.or(matches.toArray(Predicate[]::new)));
        var order = new ArrayList<>(criteria.ordering(cb, content));
        if (criteria.active() && scored) order.add(cb.desc(score));
        if (taste != null) {
            // Exclude all saved reactions, including records older than the bounded taste window.
            var excluded = query.subquery(Long.class);
            var interaction = excluded.from(UserContentInteraction.class);
            excluded.select(interaction.get("id")).where(
                    cb.equal(interaction.get("user").get("id"), userId),
                    cb.equal(interaction.get("content").get("id"), content.get("id")),
                    interaction.get("eventType").in(InteractionType.LIKE, InteractionType.BOOKMARK, InteractionType.DISLIKE));
            filters.add(cb.not(cb.exists(excluded)));
            var csv = cb.concat(cb.concat(",", cb.function("replace", String.class,
                    cb.lower(cb.coalesce(content.<String>get("genres"), "")), cb.literal(" "), cb.literal(""))), ",");
            jakarta.persistence.criteria.Expression<Integer> preference = cb.literal(0);
            for (var entry : taste.weights().entrySet()) {
                var genreMatches = genreAliases(entry.getKey()).stream()
                        .map(alias -> cb.like(csv, like("," + alias.replace(" ", "") + ","), '\\'))
                        .toArray(Predicate[]::new);
                preference = cb.sum(preference,
                        cb.<Integer>selectCase().when(cb.or(genreMatches), entry.getValue()).otherwise(0));
            }
            if (!taste.weights().isEmpty()) order.add(cb.desc(preference));
        }
        if (!criteria.active() && scored) order.add(cb.desc(score));
        order.add(cb.desc(cb.coalesce(content.<Double>get("rating"), 0.0)));
        order.add(cb.desc(cb.coalesce(content.<Long>get("viewCount"), 0L)));
        order.add(cb.asc(content.get("id")));
        query.select(content).where(filters.toArray(Predicate[]::new)).orderBy(order);
        return em.createQuery(query).setMaxResults(MAX_CANDIDATES).setHint("jakarta.persistence.query.timeout", 2000)
                .getResultList().stream().map(CatalogCandidate::from).toList();
    }

    private static String like(String term) {
        return "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    static List<String> genreAliases(String genre) {
        return GENRES.values().stream().filter(group -> group.contains(genre)).findFirst().orElse(List.of(genre));
    }

    static String canonicalGenre(String genre) {
        String value = genre.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return genreAliases(value).get(0);
    }

    private static LinkedHashSet<String> terms(String text) {
        var result = new LinkedHashSet<String>();
        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        int words = 0;
        while (matcher.find() && words++ < 32 && result.size() < 8) {
            String word = matcher.group().replaceFirst("(으로|에서|처럼|같은|추천|영화|드라마|은|는|을|를|이|가|의|도)$", "");
            if (word.length() < 2 || STOP_WORDS.contains(word) || STOP_WORDS.contains(matcher.group())) continue;
            final String keyword = word;
            var aliases = GENRES.values().stream().filter(group -> group.contains(keyword)).findFirst().orElse(List.of(word));
            for (String alias : aliases) { if (result.size() < 8) result.add(alias); }
        }
        return result;
    }

    private static String type(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean movie = lower.contains("영화") || Pattern.compile("\\bmovies?\\b").matcher(lower).find();
        boolean tv = lower.contains("드라마") || lower.contains("시리즈") || Pattern.compile("\\b(tv|series|shows?)\\b").matcher(lower).find();
        return movie == tv ? null : movie ? "MOVIE" : "TV";
    }

    private static boolean isFollowUp(String text) {
        return text.matches("(?s).*(다른|비슷|그럼|더|another|similar|more).*");
    }
}
