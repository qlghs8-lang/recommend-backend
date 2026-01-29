package com.example.recommend.service;

import com.example.recommend.domain.*;
import com.example.recommend.repository.ContentRepository;
import com.example.recommend.repository.RecommendClickLogRepository;
import com.example.recommend.repository.RecommendLogRepository;
import com.example.recommend.repository.UserContentInteractionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecommendService {

    private final ContentRepository contentRepository;
    private final UserContentInteractionRepository interactionRepository;

    private final RecommendLogRepository recommendLogRepository;
    private final RecommendClickLogRepository recommendClickLogRepository;

    private static final List<InteractionType> POSITIVE = List.of(InteractionType.LIKE, InteractionType.BOOKMARK);

    // 기존: LIKE/DISLIKE/BOOKMARK만 제외
    private static final List<InteractionType> EXCLUDE_TYPES =
            List.of(InteractionType.LIKE, InteractionType.DISLIKE, InteractionType.BOOKMARK);

    //  클릭/조회 신호 반영 파라미터
    private static final int RECENT_CLICK_LIMIT = 60;
    private static final double CLICK_GENRE_WEIGHT = 2.0;
    private static final double VIEW_PENALTY = 0.35;

    // 노출(impression) 중복 방지 윈도우
    private static final int IMPRESSION_DEDUPE_HOURS = 24;

    //  온보딩(preferredGenres) 기반 콜드스타트 가중치
    private static final double ONBOARDING_GENRE_WEIGHT = 1.5;

    // 탐색/다양성 파라미터
    private static final double EXPLORE_RATIO = 0.20;   // 20% 탐색
    private static final int EXPLORE_POOL_MULT = 6;     // 탐색 후보 풀 = exploreCount * N
    private static final long DIVERSITY_SEED_WINDOW_HOURS = 6; // 같은 시간대 결과 안정성(선택)

    @Transactional
    public List<Content> forYou(User user, int size) {
        return forYouInternal(user, size).contents;
    }

    // =========================
    // reason 포함 추천
    // =========================
    public enum RecommendSource {
        CONTENT_BASED,
        COLLABORATIVE,
        TRENDING_FALLBACK
    }

    public record RecommendItem(
            Content content,
            String reason,
            RecommendSource source,
            Long recommendLogId
    ) {}

    private static record Draft(
            Content content,
            String reason,
            RecommendSource baseSource,
            String logSource
    ) {}

    @Transactional
    public List<RecommendItem> forYouWithReasons(User user, int size) {
        ForYouInternalResult internal = forYouInternal(user, size);

        // anchors: 좋아요/찜한 콘텐츠가 없으면 빈 리스트
        List<Content> anchors = loadRecentPositiveAnchors(user.getId(), 2);

        List<String> topUserGenres = internal.genrePref.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(2)
                .toList();

        List<Draft> drafts = new ArrayList<>();
        for (Content c : internal.contents) {
            Long id = c.getId();
            if (id == null) continue;

            if (internal.fromAExploit.contains(id)) {
                String reason = buildContentBasedReason(c, anchors, topUserGenres, internal.genrePref);
                drafts.add(new Draft(
                        c,
                        reason,
                        RecommendSource.CONTENT_BASED,
                        buildLogSource(RecommendSource.CONTENT_BASED, false) // EXPLOIT
                ));

            } else if (internal.fromAExplore.contains(id)) {
                String reason = buildContentBasedReason(c, anchors, topUserGenres, internal.genrePref);
                drafts.add(new Draft(
                        c,
                        reason,
                        RecommendSource.CONTENT_BASED,
                        buildLogSource(RecommendSource.CONTENT_BASED, true) // EXPLORE
                ));

            } else if (internal.fromB.contains(id)) {
                drafts.add(new Draft(
                        c,
                        "비슷한 취향의 사용자가 좋아요/찜한 콘텐츠",
                        RecommendSource.COLLABORATIVE,
                        buildLogSource(RecommendSource.COLLABORATIVE, false) // 지금은 EXPLOIT만
                ));

            } else {
                drafts.add(new Draft(
                        c,
                        "인기 급상승(조회수/인기도) 기반 추천",
                        RecommendSource.TRENDING_FALLBACK,
                        buildLogSource(RecommendSource.TRENDING_FALLBACK, false) // 지금은 EXPLOIT만
                ));
            }
        }

        List<RecommendLog> logsAligned = saveImpressionLogsWithDedupe(user.getId(), drafts);

        List<RecommendItem> items = new ArrayList<>();
        int n = Math.min(drafts.size(), logsAligned.size());
        for (int i = 0; i < n; i++) {
            Draft d = drafts.get(i);
            RecommendLog log = logsAligned.get(i);
            if (log == null) continue;

            items.add(new RecommendItem(d.content(), d.reason(), d.baseSource(), log.getId()));
        }

        return items;
    }

    private String buildLogSource(RecommendSource base, boolean explore) {
        return base.name() + "_" + (explore ? "EXPLORE" : "EXPLOIT");
    }

    private List<RecommendLog> saveImpressionLogsWithDedupe(Long userId, List<Draft> drafts) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusHours(IMPRESSION_DEDUPE_HOURS);

        List<Long> contentIds = drafts.stream()
                .map(d -> d.content() == null ? null : d.content().getId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, RecommendLog> latestByContentId = new HashMap<>();
        if (!contentIds.isEmpty()) {
            List<RecommendLog> recent = recommendLogRepository.findRecentLogsSince(userId, contentIds, from);

            for (RecommendLog rl : recent) {
                if (rl == null || rl.getContentId() == null) continue;
                latestByContentId.putIfAbsent(rl.getContentId(), rl);
            }
        }

        List<RecommendLog> aligned = new ArrayList<>(Collections.nCopies(drafts.size(), null));

        List<RecommendLog> toInsert = new ArrayList<>();
        List<Integer> insertPositions = new ArrayList<>();

        for (int i = 0; i < drafts.size(); i++) {
            Draft d = drafts.get(i);
            Content c = d.content();
            if (c == null || c.getId() == null) continue;

            RecommendLog reuse = latestByContentId.get(c.getId());
            if (reuse != null) {
                aligned.set(i, reuse);
                continue;
            }

            RecommendLog fresh = RecommendLog.builder()
                    .userId(userId)
                    .contentId(c.getId())
                    .source(d.logSource())
                    .reason(d.reason())
                    .createdAt(now)
                    .build();

            toInsert.add(fresh);
            insertPositions.add(i);
        }

        if (!toInsert.isEmpty()) {
            List<RecommendLog> saved = recommendLogRepository.saveAll(toInsert);
            for (int k = 0; k < saved.size(); k++) {
                int pos = insertPositions.get(k);
                aligned.set(pos, saved.get(k));
            }
        }

        return aligned;
    }

    // =========================
    // 클릭 로그 저장 (토탈 클릭 정책과 호환)
    // =========================
    @Transactional
    public void logClick(User user, Long recommendLogId) {
        if (recommendLogId == null) throw new RuntimeException("recommendLogId is null");

        RecommendLog log = recommendLogRepository.findById(recommendLogId)
                .orElseThrow(() -> new RuntimeException("RecommendLog not found: " + recommendLogId));

        if (!Objects.equals(log.getUserId(), user.getId())) {
            throw new RuntimeException("Forbidden: not your recommend log");
        }

        recommendClickLogRepository.save(RecommendClickLog.builder()
                .recommendLogId(log.getId())
                .userId(user.getId())
                .contentId(log.getContentId())
                .clickedAt(LocalDateTime.now())
                .build());
    }

    // =========================
    // 내부 로직 (A/B/Fill 공통)
    // =========================
    private static class ForYouInternalResult {
        List<Content> contents;
        Map<String, Double> genrePref;

        // A를 exploit/explore로 분리해서 태깅
        Set<Long> fromAExploit;
        Set<Long> fromAExplore;

        // 협업/기타
        Set<Long> fromB;

        ForYouInternalResult(List<Content> contents,
                             Map<String, Double> genrePref,
                             Set<Long> fromAExploit,
                             Set<Long> fromAExplore,
                             Set<Long> fromB) {
            this.contents = contents;
            this.genrePref = genrePref;
            this.fromAExploit = fromAExploit;
            this.fromAExplore = fromAExplore;
            this.fromB = fromB;
        }
    }

    @Transactional
    private ForYouInternalResult forYouInternal(User user, int size) {

        Set<Long> exclude = new HashSet<>(
                interactionRepository.findContentIdsByUserAndTypes(user.getId(), EXCLUDE_TYPES)
        );

        Set<Long> disliked = new HashSet<>(
                interactionRepository.findContentIdsByUserAndTypes(user.getId(), List.of(InteractionType.DISLIKE))
        );

        Set<Long> viewed = new HashSet<>(
                interactionRepository.findContentIdsByUserAndTypes(user.getId(), List.of(InteractionType.VIEW))
        );

        List<Long> positiveContentIds =
                interactionRepository.findContentIdsByUserAndTypes(user.getId(), POSITIVE);

        List<Content> all = contentRepository.findAll();

        // 콜드스타트 분기 (preferredGenres)
        if (positiveContentIds.isEmpty()) {

            Map<String, Double> onboardingPref = genrePrefFromPreferredGenres(user);
            if (onboardingPref.isEmpty()) {
                List<Content> cold = all.stream()
                        .sorted(Comparator.comparing((Content c) -> Optional.ofNullable(c.getViewCount()).orElse(0L)).reversed())
                        .limit(size)
                        .toList();

                // cold는 전부 fallback 취급 → fromAExploit/fromAExplore 비움
                return new ForYouInternalResult(cold, Map.of(), Set.of(), Set.of(), Set.of());
            }

            long maxView = all.stream()
                    .map(Content::getViewCount)
                    .filter(Objects::nonNull)
                    .max(Long::compareTo)
                    .orElse(1L);

            Map<Long, Double> scoreA = new HashMap<>();
            for (Content c : all) {
                if (c.getId() == null) continue;
                if (exclude.contains(c.getId())) continue;
                if (disliked.contains(c.getId())) continue;

                double genreScore = scoreByGenres(c, onboardingPref);
                if (genreScore <= 0) {
                    scoreA.put(c.getId(), 0.0);
                    continue;
                }

                double rating = c.getRating() == null ? 0.0 : c.getRating();
                double viewNorm = (c.getViewCount() == null ? 0.0 : (double) c.getViewCount() / (double) maxView);

                double s =
                        (genreScore * 1.0) +
                        (rating * 0.12) +
                        (viewNorm * 0.35);

                if (viewed.contains(c.getId())) s = s * VIEW_PENALTY;

                scoreA.put(c.getId(), s);
            }

            List<Long> rankedA = scoreA.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .filter(id -> scoreA.getOrDefault(id, 0.0) > 0)
                    .toList();

            int exploreCount = (int) Math.round(size * EXPLORE_RATIO);
            exploreCount = Math.max(0, Math.min(exploreCount, size));

            int exploitCount = Math.max(0, size - exploreCount);
            List<Long> exploitIds = rankedA.stream().limit(exploitCount).toList();
            List<Long> exploreIds = pickExploreFromRanked(
                    user.getId(), rankedA, scoreA, onboardingPref, viewed, exploitIds, exploreCount, all
            );

            LinkedHashSet<Long> merged = new LinkedHashSet<>();
            merged.addAll(exploitIds);
            merged.addAll(exploreIds);

            if (merged.size() < size) {
                List<Long> fallback1 = all.stream()
                        .sorted(Comparator.comparing((Content c) -> Optional.ofNullable(c.getViewCount()).orElse(0L)).reversed())
                        .map(Content::getId)
                        .filter(Objects::nonNull)
                        .filter(id -> !exclude.contains(id))
                        .filter(id -> !disliked.contains(id))
                        .toList();

                for (Long id : fallback1) {
                    merged.add(id);
                    if (merged.size() >= size) break;
                }

                if (merged.size() < size) {
                    List<Long> fallback2 = all.stream()
                            .sorted(Comparator.comparing((Content c) -> Optional.ofNullable(c.getViewCount()).orElse(0L)).reversed())
                            .map(Content::getId)
                            .filter(Objects::nonNull)
                            .filter(id -> !disliked.contains(id))
                            .toList();

                    for (Long id : fallback2) {
                        merged.add(id);
                        if (merged.size() >= size) break;
                    }
                }
            }

            List<Long> resultIds = merged.stream().limit(size).toList();
            Map<Long, Content> map = contentRepository.findByIdIn(resultIds).stream()
                    .collect(Collectors.toMap(Content::getId, c -> c));

            List<Content> result = new ArrayList<>();
            for (Long id : resultIds) {
                Content c = map.get(id);
                if (c != null) result.add(c);
            }

            return new ForYouInternalResult(
                    result,
                    onboardingPref,
                    new HashSet<>(exploitIds),
                    new HashSet<>(exploreIds),
                    Set.of()
            );
        }

        List<Long> recentClickedContentIds = recommendClickLogRepository.findRecentClickedContentIds(
                user.getId(),
                PageRequest.of(0, RECENT_CLICK_LIMIT)
        );

        Map<String, Double> genrePref = buildGenrePreference(positiveContentIds, recentClickedContentIds);

        long maxView = all.stream()
                .map(Content::getViewCount)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(1L);

        Map<Long, Double> scoreA = new HashMap<>();

        for (Content c : all) {
            if (c.getId() == null) continue;
            if (exclude.contains(c.getId())) continue;
            if (disliked.contains(c.getId())) continue;

            double genreScore = scoreByGenres(c, genrePref);
            if (genreScore <= 0) {
                scoreA.put(c.getId(), 0.0);
                continue;
            }

            double rating = c.getRating() == null ? 0.0 : c.getRating();
            double viewNorm = (c.getViewCount() == null ? 0.0 : (double) c.getViewCount() / (double) maxView);

            double s =
                    (genreScore * 1.0) +
                    (rating * 0.12) +
                    (viewNorm * 0.35);

            if (viewed.contains(c.getId())) s = s * VIEW_PENALTY;

            scoreA.put(c.getId(), s);
        }

        int sizeA = Math.max(1, (int) Math.round(size * 0.8));

        List<Long> rankedA = scoreA.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .filter(id -> scoreA.getOrDefault(id, 0.0) > 0)
                .toList();

        int exploreCount = (int) Math.round(sizeA * EXPLORE_RATIO);
        exploreCount = Math.max(0, Math.min(exploreCount, sizeA));

        int exploitCount = Math.max(0, sizeA - exploreCount);

        List<Long> exploitA = rankedA.stream().limit(exploitCount).toList();
        List<Long> exploreA = pickExploreFromRanked(
                user.getId(),
                rankedA,
                scoreA,
                genrePref,
                viewed,
                exploitA,
                exploreCount,
                all
        );

        List<Long> topA = new ArrayList<>(exploitA.size() + exploreA.size());
        topA.addAll(exploitA);
        topA.addAll(exploreA);

        int sizeB = Math.max(1, size - topA.size());
        List<Long> topB = collaborativeIds(user.getId(), positiveContentIds, exclude, disliked, sizeB, all);

        LinkedHashSet<Long> merged = new LinkedHashSet<>();
        merged.addAll(topA);
        merged.addAll(topB);

        Set<Long> fromAExploit = new HashSet<>(exploitA);
        Set<Long> fromAExplore = new HashSet<>(exploreA);
        Set<Long> fromB = new HashSet<>(topB);

        if (merged.size() < size) {
            List<Long> fallback1 = all.stream()
                    .sorted(Comparator.comparing((Content c) -> Optional.ofNullable(c.getViewCount()).orElse(0L)).reversed())
                    .map(Content::getId)
                    .filter(Objects::nonNull)
                    .filter(id -> !exclude.contains(id))
                    .filter(id -> !disliked.contains(id))
                    .toList();

            for (Long id : fallback1) {
                merged.add(id);
                if (merged.size() >= size) break;
            }

            if (merged.size() < size) {
                List<Long> fallback2 = all.stream()
                        .sorted(Comparator.comparing((Content c) -> Optional.ofNullable(c.getViewCount()).orElse(0L)).reversed())
                        .map(Content::getId)
                        .filter(Objects::nonNull)
                        .filter(id -> !disliked.contains(id))
                        .toList();

                for (Long id : fallback2) {
                    merged.add(id);
                    if (merged.size() >= size) break;
                }
            }
        }

        List<Long> resultIds = merged.stream().limit(size).toList();
        Map<Long, Content> map = contentRepository.findByIdIn(resultIds).stream()
                .collect(Collectors.toMap(Content::getId, c -> c));

        List<Content> result = new ArrayList<>();
        for (Long id : resultIds) {
            Content c = map.get(id);
            if (c != null) result.add(c);
        }

        return new ForYouInternalResult(result, genrePref, fromAExploit, fromAExplore, fromB);
    }

    private String buildContentBasedReason(Content rec,
                                          List<Content> anchors,
                                          List<String> topUserGenres,
                                          Map<String, Double> genrePref) {

        List<String> recGenres = parseGenres(rec.getGenres());
        if (recGenres.isEmpty()) return "선호 장르 기반 추천";

        List<String> matched = new ArrayList<>();
        for (String g : topUserGenres) {
            if (recGenres.contains(g)) matched.add(g);
        }

        if (matched.isEmpty()) {
            matched = recGenres.stream()
                    .sorted((a, b) -> Double.compare(
                            genrePref.getOrDefault(b, 0.0),
                            genrePref.getOrDefault(a, 0.0)
                    ))
                    .limit(2)
                    .toList();
        } else {
            matched = matched.stream().limit(2).toList();
        }

        List<String> prettyGenres = matched.stream().map(this::prettyGenre).toList();
        String genreText = prettyGenres.isEmpty() ? "선호 장르" : String.join(", ", prettyGenres);

        Content bestAnchor = pickBestAnchorByGenreOverlap(anchors, recGenres);

        if (bestAnchor != null && bestAnchor.getTitle() != null && !bestAnchor.getTitle().isBlank()) {
            return "최근 좋아요/찜한 '" + bestAnchor.getTitle() + "'와 비슷한 " + genreText + " 장르라 추천";
        }

        return genreText + " 장르를 좋아해서 추천";
    }

    private Content pickBestAnchorByGenreOverlap(List<Content> anchors, List<String> recGenresLower) {
        if (anchors == null || anchors.isEmpty()) return null;

        Content best = null;
        int bestOverlap = 0;

        for (Content a : anchors) {
            List<String> ag = parseGenres(a.getGenres());
            int overlap = 0;
            for (String g : ag) {
                if (recGenresLower.contains(g)) overlap++;
            }
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = a;
            }
        }
        return bestOverlap > 0 ? best : null;
    }

    private List<Content> loadRecentPositiveAnchors(Long userId, int limit) {
        List<Long> ids = interactionRepository.findRecentContentIdsByUserAndTypes(
                userId,
                POSITIVE,
                PageRequest.of(0, limit)
        );

        if (ids == null || ids.isEmpty()) return List.of();

        Map<Long, Content> map = contentRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(Content::getId, c -> c, (a, b) -> a));

        List<Content> ordered = new ArrayList<>();
        for (Long id : ids) {
            Content c = map.get(id);
            if (c != null) ordered.add(c);
        }
        return ordered;
    }

    // Warm start: 장르 선호 생성 (좋아요/찜 + 클릭)
    private Map<String, Double> buildGenrePreference(List<Long> positiveContentIds,
                                                     List<Long> recentClickedContentIds) {

        Map<Long, Content> byId = contentRepository.findAll().stream()
                .filter(c -> c.getId() != null)
                .collect(Collectors.toMap(Content::getId, c -> c, (a, b) -> a));

        Map<String, Double> pref = new HashMap<>();

        for (Long id : positiveContentIds) {
            Content c = byId.get(id);
            if (c == null) continue;
            for (String g : parseGenres(c.getGenres())) {
                pref.put(g, pref.getOrDefault(g, 0.0) + 1.0);
            }
        }

        if (recentClickedContentIds != null) {
            for (Long id : recentClickedContentIds) {
                Content c = byId.get(id);
                if (c == null) continue;
                for (String g : parseGenres(c.getGenres())) {
                    pref.put(g, pref.getOrDefault(g, 0.0) + CLICK_GENRE_WEIGHT);
                }
            }
        }

        return pref;
    }

    private Map<String, Double> genrePrefFromPreferredGenres(User user) {
        if (user == null) return Map.of();

        String csv = null;
        try {
            csv = user.getPreferredGenres();
        } catch (Exception ignored) {}

        if (csv == null || csv.isBlank()) return Map.of();

        Map<String, Double> pref = new HashMap<>();
        for (String g : parseGenres(csv)) {
            pref.put(g, pref.getOrDefault(g, 0.0) + ONBOARDING_GENRE_WEIGHT);
        }
        return pref;
    }

    private double scoreByGenres(Content content, Map<String, Double> genrePref) {
        List<String> genres = parseGenres(content.getGenres());
        if (genres.isEmpty() || genrePref.isEmpty()) return 0.0;

        double s = 0.0;
        for (String g : genres) {
            double pref = genrePref.getOrDefault(g, 0.0);
            if (pref > 0) s += pref;
        }
        return s;
    }

    private List<Long> collaborativeIds(Long userId,
                                        List<Long> positiveContentIds,
                                        Set<Long> exclude,
                                        Set<Long> disliked,
                                        int sizeB,
                                        List<Content> all) {

        List<Long> similarUserIds = interactionRepository.findOtherUserIdsWhoInteracted(
                userId, positiveContentIds, POSITIVE
        );
        if (similarUserIds.isEmpty()) return List.of();

        List<Long> candidates = interactionRepository.findContentIdsByUserIdsAndTypes(similarUserIds, POSITIVE);

        Map<Long, Integer> freq = new HashMap<>();
        for (Long cid : candidates) {
            if (cid == null) continue;
            if (exclude.contains(cid)) continue;
            if (disliked.contains(cid)) continue;
            freq.put(cid, freq.getOrDefault(cid, 0) + 1);
        }

        Map<Long, Content> byId = all.stream()
                .filter(c -> c.getId() != null)
                .collect(Collectors.toMap(Content::getId, c -> c, (a, b) -> a));

        return freq.entrySet().stream()
                .sorted((e1, e2) -> {
                    int cmp = Integer.compare(e2.getValue(), e1.getValue());
                    if (cmp != 0) return cmp;

                    Content c1 = byId.get(e1.getKey());
                    Content c2 = byId.get(e2.getKey());
                    long v1 = c1 == null || c1.getViewCount() == null ? 0L : c1.getViewCount();
                    long v2 = c2 == null || c2.getViewCount() == null ? 0L : c2.getViewCount();
                    return Long.compare(v2, v1);
                })
                .map(Map.Entry::getKey)
                .limit(sizeB)
                .toList();
    }

    private List<String> parseGenres(String genresCsv) {
        if (genresCsv == null || genresCsv.isBlank()) return List.of();
        return Arrays.stream(genresCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(String::toLowerCase)
                .toList();
    }

    private String prettyGenre(String g) {
        if (g == null || g.isBlank()) return "";
        String s = g.trim().toLowerCase();

        if (s.equals("sci-fi") || s.equals("scifi") || s.equals("sci fi")) return "Sci-Fi";

        String[] parts = s.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isBlank()) continue;
            sb.append(p.substring(0, 1).toUpperCase()).append(p.substring(1));
            if (i < parts.length - 1) sb.append(" ");
        }
        return sb.toString();
    }

    private List<Long> pickExploreFromRanked(
            Long userId,
            List<Long> ranked,
            Map<Long, Double> scoreA,
            Map<String, Double> genrePref,
            Set<Long> viewed,
            List<Long> alreadyPicked,
            int exploreCount,
            List<Content> all
    ) {
        if (exploreCount <= 0) return List.of();

        Set<Long> picked = new HashSet<>(alreadyPicked);

        Map<Long, Content> byId = all.stream()
                .filter(c -> c.getId() != null)
                .collect(Collectors.toMap(Content::getId, c -> c, (a, b) -> a));

        int poolSize = Math.min(ranked.size(), exploreCount * EXPLORE_POOL_MULT + alreadyPicked.size());
        List<Long> pool = ranked.stream()
                .limit(poolSize)
                .filter(id -> !picked.contains(id))
                .toList();

        if (pool.isEmpty()) return List.of();

        long bucket = (System.currentTimeMillis() / 1000L) / (DIVERSITY_SEED_WINDOW_HOURS * 3600L);
        Random rnd = new Random(Objects.hash(userId, bucket));

        List<Long> result = new ArrayList<>();
        for (int i = 0; i < exploreCount; i++) {
            Long chosen = weightedPickOnce(pool, byId, scoreA, genrePref, viewed, rnd);
            if (chosen == null) break;
            result.add(chosen);
            pool = pool.stream().filter(id -> !id.equals(chosen)).toList();
            if (pool.isEmpty()) break;
        }
        return result;
    }

    private Long weightedPickOnce(
            List<Long> pool,
            Map<Long, Content> byId,
            Map<Long, Double> scoreA,
            Map<String, Double> genrePref,
            Set<Long> viewed,
            Random rnd
    ) {
        double total = 0.0;
        double[] w = new double[pool.size()];

        for (int i = 0; i < pool.size(); i++) {
            Long id = pool.get(i);
            Content c = byId.get(id);
            if (c == null) {
                w[i] = 0;
                continue;
            }

            double base = Math.max(0.01, scoreA.getOrDefault(id, 0.01));
            double rarity = genreRarityWeight(c.getGenres(), genrePref);
            double novelty = viewed.contains(id) ? 0.25 : 1.0;

            double weight = base * rarity * novelty;

            w[i] = weight;
            total += weight;
        }

        if (total <= 0) return null;

        double r = rnd.nextDouble() * total;
        double acc = 0.0;
        for (int i = 0; i < pool.size(); i++) {
            acc += w[i];
            if (r <= acc) return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }

    private double genreRarityWeight(String genresCsv, Map<String, Double> genrePref) {
        List<String> gs = parseGenres(genresCsv);
        if (gs.isEmpty()) return 1.0;

        double minPref = Double.MAX_VALUE;
        for (String g : gs) {
            double p = genrePref.getOrDefault(g, 0.0);
            if (p < minPref) minPref = p;
        }

        return 1.0 + (1.2 / (1.0 + minPref));
    }
}

