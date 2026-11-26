package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.client.RecommendationConstraint.Kind;
import com.example.recommend.domain.Content;
import jakarta.persistence.criteria.*;
import java.time.LocalDate;
import java.util.*;

/** Server execution of allowlisted criteria. Subjective preferences rank; they do not certify suitability. */
public record RecommendationCriteria(List<RecommendationConstraint> constraints, Set<Long> onlyIds,
        Set<Long> excludedIds, boolean missingReference) {
    public static final RecommendationCriteria NONE = new RecommendationCriteria(List.of(), Set.of(), Set.of(), false);
    public RecommendationCriteria {
        constraints = List.copyOf(constraints); onlyIds = Set.copyOf(onlyIds); excludedIds = Set.copyOf(excludedIds);
    }

    public static RecommendationCriteria from(List<RecommendationConstraint> constraints, List<AiMessage> context, String question) {
        if (constraints.size() > 8) throw ChatException.invalidResponse();
        var previous = context.subList(0, context.size() - 1).stream().filter(m -> m.role() == AiMessage.Role.USER).map(AiMessage::text).toList();
        for (var constraint : constraints) if (!question.contains(constraint.source())
                && previous.stream().noneMatch(text -> text.contains(constraint.source()))) throw ChatException.invalidResponse();
        var only = new LinkedHashSet<Long>(); var excluded = new LinkedHashSet<Long>(); boolean missing = false;
        for (var constraint : constraints) if (constraint.kind() == Kind.REFERENCE) {
            if (constraint.value().equals("ONLY_LAST")) {
                // The immediately prior assistant turn must expose trusted public IDs. Never skip a hidden/private list.
                var last = context.subList(0, context.size() - 1).stream().filter(m -> m.role() == AiMessage.Role.ASSISTANT).reduce((a, b) -> b);
                if (last.isEmpty() || last.get().contentIds().isEmpty()) missing = true;
                else only.addAll(last.get().contentIds());
            } else {
                context.stream().filter(m -> m.role() == AiMessage.Role.ASSISTANT).forEach(m -> excluded.addAll(m.contentIds()));
            }
        }
        return new RecommendationCriteria(constraints, only, excluded, missing);
    }

    public boolean active() { return !constraints.isEmpty(); }
    public boolean subjective() { return constraints.stream().anyMatch(c -> c.kind() == Kind.MOOD || c.kind() == Kind.SITUATION); }
    public boolean runtime() { return constraints.stream().anyMatch(c -> c.kind().name().contains("MINUTES_")
            || c.kind() == Kind.ORDER && c.value().startsWith("SHORTER_")); }
    public RecommendationCriteria shortestKnown(List<CatalogCandidate> candidates) {
        if (constraints.stream().noneMatch(c -> c.kind() == Kind.ORDER && c.value().startsWith("SHORTER_")) || candidates.isEmpty()) return this;
        int shortest = candidates.stream().mapToInt(c -> episode() ? c.episodeRuntimeMinutes() : c.runtimeMinutes()).min().orElseThrow();
        var ids = new LinkedHashSet<Long>();
        candidates.stream().filter(c -> (episode() ? c.episodeRuntimeMinutes() : c.runtimeMinutes()) == shortest).forEach(c -> ids.add(c.id()));
        return new RecommendationCriteria(constraints, ids, excludedIds, missingReference);
    }
    private boolean episode() { return constraints.stream().anyMatch(c -> c.kind().name().startsWith("EPISODE_")
            || c.kind() == Kind.ORDER && c.value().equals("SHORTER_EPISODE")); }
    private boolean movieDuration() { return constraints.stream().anyMatch(c -> c.kind().name().startsWith("MINUTES_")
            || c.kind() == Kind.ORDER && c.value().equals("SHORTER_MOVIE")); }
    private Set<String> values(Kind kind) {
        var result = new LinkedHashSet<String>(); constraints.stream().filter(c -> c.kind() == kind).forEach(c -> result.add(c.value())); return result;
    }
    private int lower(Kind kind, int fallback) { return values(kind).stream().mapToInt(Integer::parseInt).max().orElse(fallback); }
    private int upper(Kind kind, int fallback) { return values(kind).stream().mapToInt(Integer::parseInt).min().orElse(fallback); }

    public String clarification() {
        if (missingReference) return "앞 답변의 공개 작품을 확인할 수 없어요. 작품 제목을 지정하거나 새 추천 조건을 알려 주세요.";
        var unverifiable = constraints.stream().filter(c -> c.kind() == Kind.UNVERIFIABLE || c.kind() == Kind.AVOID).toList();
        if (!unverifiable.isEmpty()) return "현재 카탈로그 정보로 확인하거나 보장할 수 없는 조건이 있어요: "
                + String.join(" · ", unverifiable.stream().map(RecommendationConstraint::source).toList())
                + ". 해당 정보가 없다는 것을 조건 충족으로 간주하지 않아요. 장르·유형·공개 연도 등 확인 가능한 조건으로 바꿔 주세요.";
        var media = values(Kind.MEDIA);
        var requiredGenres = values(Kind.GENRE_ALL);
        if (media.size() > 1 || episode() && movieDuration() || episode() && media.contains("MOVIE") || movieDuration() && media.contains("TV")
                || lower(Kind.YEAR_MIN, 1800) > upper(Kind.YEAR_MAX, 2200)
                || lower(Kind.MINUTES_MIN, 1) > upper(Kind.MINUTES_MAX, 1440)
                || lower(Kind.EPISODE_MINUTES_MIN, 1) > upper(Kind.EPISODE_MINUTES_MAX, 1440)
                || requiredGenres.stream().anyMatch(values(Kind.GENRE_EXCLUDE)::contains)
                || !values(Kind.GENRE_ANY).isEmpty() && values(Kind.GENRE_EXCLUDE).containsAll(values(Kind.GENRE_ANY)))
            return "함께 적용하기 어려운 추천 조건이 있어요. 영화 전체 길이와 시리즈 한 회 길이를 구분하고, 포함·제외 조건이나 범위를 다시 알려 주세요.";
        return null;
    }

    public boolean matches(CatalogCandidate work) {
        if (!onlyIds.isEmpty() && !onlyIds.contains(work.id()) || excludedIds.contains(work.id())) return false;
        if (movieDuration() && !work.type().equalsIgnoreCase("MOVIE") || episode() && !work.type().equalsIgnoreCase("TV")) return false;
        var any = values(Kind.GENRE_ANY);
        if (!any.isEmpty() && any.stream().noneMatch(g -> genre(work.genres(), g))) return false;
        for (var c : constraints) {
            boolean match = switch (c.kind()) {
                case MEDIA -> work.type().equalsIgnoreCase(c.value());
                case GENRE_ALL -> genre(work.genres(), c.value());
                case GENRE_EXCLUDE -> !work.genres().isBlank() && !genre(work.genres(), c.value());
                case YEAR_MIN -> year(work) >= Integer.parseInt(c.value());
                case YEAR_MAX -> year(work) != 0 && year(work) <= Integer.parseInt(c.value());
                case MINUTES_MIN -> duration(work.runtimeMinutes()) && work.runtimeMinutes() >= Integer.parseInt(c.value());
                case MINUTES_MAX -> duration(work.runtimeMinutes()) && work.runtimeMinutes() <= Integer.parseInt(c.value());
                case EPISODE_MINUTES_MIN -> duration(work.episodeRuntimeMinutes()) && work.episodeRuntimeMinutes() >= Integer.parseInt(c.value());
                case EPISODE_MINUTES_MAX -> duration(work.episodeRuntimeMinutes()) && work.episodeRuntimeMinutes() <= Integer.parseInt(c.value());
                case SETTING -> c.terms().stream().anyMatch(t -> (work.title() + "\n" + work.overview()).toLowerCase(Locale.ROOT).contains(t.toLowerCase(Locale.ROOT)));
                case TOPIC -> c.terms().stream().anyMatch(t -> topicMatches(work.title() + "\n" + work.overview(), t));
                case TITLE_EXCLUDE -> !work.title().equalsIgnoreCase(c.value());
                case ORDER -> !c.value().startsWith("SHORTER_") || duration(c.value().equals("SHORTER_MOVIE") ? work.runtimeMinutes() : work.episodeRuntimeMinutes());
                default -> true;
            };
            if (!match) return false;
        }
        return true;
    }
    private static boolean duration(Integer value) { return value != null && value > 0 && value <= 1440; }
    private static int year(CatalogCandidate work) {
        try { return LocalDate.parse(work.releaseDate()).getYear(); } catch (RuntimeException e) { return 0; }
    }
    private static boolean genre(String csv, String requested) { return ChatTaste.genres(csv).contains(CatalogRetriever.canonicalGenre(requested)); }
    private static String like(String value) { return "%" + value.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }
    private static Expression<String> csv(CriteriaBuilder cb, Root<Content> work) {
        return cb.concat(cb.concat(",", cb.function("replace", String.class, cb.lower(cb.coalesce(work.<String>get("genres"), "")), cb.literal(" "), cb.literal(""))), ",");
    }
    private static Predicate genre(CriteriaBuilder cb, Root<Content> work, String value) {
        return cb.or(CatalogRetriever.genreAliases(value).stream().map(g -> cb.like(csv(cb, work), like("," + g.replace(" ", "") + ","), '\\')).toArray(Predicate[]::new));
    }

    public List<Predicate> predicates(CriteriaBuilder cb, Root<Content> work) {
        var result = new ArrayList<Predicate>();
        if (!onlyIds.isEmpty()) result.add(work.get("id").in(onlyIds));
        if (!excludedIds.isEmpty()) result.add(cb.not(work.get("id").in(excludedIds)));
        if (movieDuration() || episode()) result.add(cb.equal(cb.upper(work.get("type")), episode() ? "TV" : "MOVIE"));
        var any = values(Kind.GENRE_ANY);
        if (!any.isEmpty()) result.add(cb.or(any.stream().map(g -> genre(cb, work, g)).toArray(Predicate[]::new)));
        for (var c : constraints) switch (c.kind()) {
            case MEDIA -> result.add(cb.equal(cb.upper(work.get("type")), c.value()));
            case GENRE_ALL -> result.add(genre(cb, work, c.value()));
            case GENRE_EXCLUDE -> result.add(cb.and(cb.notEqual(cb.trim(cb.coalesce(work.<String>get("genres"), "")), ""), cb.not(genre(cb, work, c.value()))));
            case YEAR_MIN -> result.add(cb.greaterThanOrEqualTo(work.get("releaseDate"), LocalDate.of(Integer.parseInt(c.value()), 1, 1)));
            case YEAR_MAX -> result.add(cb.lessThan(work.get("releaseDate"), LocalDate.of(Integer.parseInt(c.value()) + 1, 1, 1)));
            case MINUTES_MIN, MINUTES_MAX, EPISODE_MINUTES_MIN, EPISODE_MINUTES_MAX -> {
                var field = work.<Integer>get(c.kind().name().startsWith("EPISODE_") ? "episodeRuntimeMinutes" : "runtimeMinutes");
                result.add(cb.between(field, 1, 1440));
                result.add(c.kind().name().endsWith("MIN") ? cb.ge(field, Integer.parseInt(c.value())) : cb.le(field, Integer.parseInt(c.value())));
            }
            case SETTING, TOPIC -> {
                var title = cb.lower(cb.coalesce(work.<String>get("title"), ""));
                var overview = cb.lower(cb.substring(cb.coalesce(work.<String>get("overview"), ""), 1, 400));
                result.add(cb.or(c.terms().stream().map(t -> c.kind() == Kind.TOPIC
                        ? cb.or(cb.isTrue(cb.function("regexp_like", Boolean.class, title, cb.literal(RecommendationConstraint.topicPattern(t)))),
                                cb.isTrue(cb.function("regexp_like", Boolean.class, overview, cb.literal(RecommendationConstraint.topicPattern(t)))))
                        : cb.or(cb.like(title, like(t), '\\'), cb.like(overview, like(t), '\\'))).toArray(Predicate[]::new)));
            }
            case TITLE_EXCLUDE -> result.add(cb.notEqual(cb.lower(work.get("title")), c.value().toLowerCase(Locale.ROOT)));
            case ORDER -> { if (c.value().startsWith("SHORTER_")) result.add(cb.between(work.get(c.value().equals("SHORTER_MOVIE") ? "runtimeMinutes" : "episodeRuntimeMinutes"), 1, 1440)); }
            default -> { }
        }
        return result;
    }

    public List<jakarta.persistence.criteria.Order> ordering(CriteriaBuilder cb, Root<Content> work) {
        var result = new ArrayList<jakarta.persistence.criteria.Order>();
        for (var order : values(Kind.ORDER)) result.add(order.equals("NEWER") ? cb.desc(work.get("releaseDate"))
                : cb.asc(work.get(order.equals("SHORTER_MOVIE") ? "runtimeMinutes" : "episodeRuntimeMinutes")));
        return result;
    }
    public List<String> rankingTerms() { return constraints.stream().filter(c -> c.kind() == Kind.MOOD || c.kind() == Kind.SITUATION || c.kind() == Kind.SETTING || c.kind() == Kind.TOPIC)
            .flatMap(c -> c.terms().stream()).distinct().toList(); }
    public void validateTopicEvidence(CatalogRationale rationale) {
        var topics = constraints.stream().filter(c -> c.kind() == Kind.TOPIC).toList();
        if (!topics.isEmpty() && rationale.evidence().stream().noneMatch(e -> e.field() != CatalogRationale.Field.GENRES
                && topics.stream().flatMap(c -> c.terms().stream()).anyMatch(term ->
                        topicMatches(e.quote(), term))))
            throw ChatException.invalidResponse();
    }
    private static boolean topicMatches(String text, String term) {
        return java.util.regex.Pattern.compile(RecommendationConstraint.topicPattern(term))
                .matcher(text.toLowerCase(Locale.ROOT)).find();
    }
    public String summary() {
        return "적용한 요청 조건: " + String.join(" · ", constraints.stream().map(RecommendationConstraint::source).distinct().toList())
                + (subjective() ? "\n분위기·감상 상황에 대한 적합성은 카탈로그 근거를 바탕으로 한 해석이에요." : "");
    }
    public String noMatch() {
        return "요청 조건과 확인 가능한 카탈로그 정보가 함께 맞는 작품을 찾지 못했어요."
                + (constraints.stream().anyMatch(c -> c.kind() == Kind.TOPIC)
                    ? " 요청한 주제와 다른 조건을 함께 뒷받침할 제목·줄거리 근거가 현재 카탈로그에 부족해요: "
                        + String.join(" · ", constraints.stream().filter(c -> c.kind() == Kind.TOPIC).map(RecommendationConstraint::source).distinct().toList()) + "." : "")
                + (runtime() ? " 러닝타임이 미등록되거나 유효하지 않은 작품은 시간 조건에서 제외했어요." : "")
                + " 조건을 완화하거나 다른 조건을 말씀해 주세요.";
    }
}
