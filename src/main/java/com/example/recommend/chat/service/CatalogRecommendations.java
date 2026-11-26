package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.repository.CatalogSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CatalogRecommendations {
    private final CatalogRetriever retriever;
    private final AiClient client;
    private final CatalogSnapshotRepository contents;
    private final CatalogToolService tools;
    private final ChatInteractionReader interactions;
    private static final String NO_MATCH = "현재 검색한 카탈로그 후보에서 조건에 맞는 작품을 찾지 못했어요. 원하는 제목이나 장르, 영화/시리즈 여부를 조금 더 구체적으로 알려 주세요.";

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChatResponse explainRecommendations(List<Long> ids, String originalRequest, String question, TurnBudget budget) {
        if (ids == null || ids.isEmpty() || ids.size() > 5 || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> id == null || id <= 0)) throw ChatException.invalidResponse();
        var works = new ArrayList<CatalogCandidate>();
        for (long id : ids) {
            budget.remainingTime();
            var work = retriever.detail(id).orElse(null);
            if (work == null) return new ChatResponse("앞서 안내한 작품 중 현재 카탈로그에서 찾을 수 없는 작품이 있어요. 새 추천을 받아 주세요.",
                    budget.usage(), false);
            works.add(work);
        }
        var informative = works.stream().filter(w -> !w.genres().isBlank() || !w.overview().isBlank()).toList();
        Map<Long, CatalogRationale> explanations = informative.isEmpty() ? Map.of()
                : client.justifyMany(originalRequest, question, informative, budget);
        var expected = informative.stream().map(CatalogCandidate::id).collect(Collectors.toSet());
        if (explanations == null || !explanations.keySet().equals(expected)) throw ChatException.invalidResponse();
        for (var work : works) {
            budget.remainingTime();
            if (!retriever.detail(work.id()).filter(work::equals).isPresent()) throw ChatException.invalidResponse();
            if (explanations.containsKey(work.id())) {
                var rationale = explanations.get(work.id());
                if (rationale == null) throw ChatException.invalidResponse();
                rationale.validate(work);
            }
        }
        var text = new StringBuilder("앞서 추천한 작품들의 이유를 각각 설명할게요. 당시 요청과 현재 카탈로그 정보를 바탕으로 했어요.");
        for (int i = 0; i < works.size(); i++) {
            var work = works.get(i);
            text.append("\n\n[").append(i + 1).append("] ").append(work.title()).append(" (ID: ").append(work.id()).append(")");
            var rationale = explanations.get(work.id());
            if (rationale == null || rationale.evidence().isEmpty()) text.append("\n요청 조건에 맞는다고 설명할 카탈로그 근거가 부족해요.");
            else {
                for (var evidence : rationale.evidence()) text.append("\n카탈로그 근거: ").append(evidence.quote());
                text.append("\n추천 이유(모델의 해석): ").append(rationale.interpretation());
            }
        }
        text.append("\n\n특정 작품만 더 궁금하면 제목이나 순서를 말씀해 주세요. 계절감·분위기나 개인 취향 적합성은 확정하기 어려워요.");
        budget.remainingTime();
        return new ChatResponse(text.toString(), budget.usage(), true, ids);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChatResponse explainRecommendation(long contentId, String originalRequest, String question, TurnBudget budget) {
        budget.remainingTime();
        var work = retriever.detail(contentId).orElse(null);
        if (work == null) return new ChatResponse("앞서 안내한 작품을 현재 카탈로그에서 찾지 못했어요. 다른 작품을 골라 주세요.",
                budget.usage(), false);
        if (work.genres().isBlank() && work.overview().isBlank())
            return new ChatResponse(work.title() + " (ID: " + work.id() + ")\n추천 근거를 설명할 장르·줄거리 정보가 부족해요.",
                    budget.usage(), true, List.of(work.id()));
        var rationale = client.justify(originalRequest, question, work, budget);
        if (rationale == null) throw ChatException.invalidResponse();
        // Recheck public data after the network call; removed/changed works must not ground stale evidence.
        var current = retriever.detail(contentId).orElseThrow(ChatException::invalidResponse);
        if (!current.equals(work)) throw ChatException.invalidResponse();
        rationale.validate(current);
        budget.remainingTime();
        var text = new StringBuilder("앞서 안내한 ").append(current.title()).append(" (ID: ").append(current.id())
                .append(")에 대해, 당시 요청과 현재 카탈로그 정보를 바탕으로 설명할게요.");
        if (rationale.evidence().isEmpty()) text.append("\n\n요청 조건에 잘 맞는다고 설명할 카탈로그 근거가 부족해요.");
        else {
            text.append("\n\n카탈로그에서 확인한 근거:");
            for (var item : rationale.evidence()) text.append("\n- ")
                    .append(switch (item.field()) { case TITLE -> "제목"; case GENRES -> "장르"; case OVERVIEW -> "줄거리"; })
                    .append(": ").append(item.quote());
            text.append("\n\n요청과의 연결(모델의 해석): ").append(rationale.interpretation());
        }
        text.append("\n\n계절감·분위기나 개인 취향에 맞는지는 카탈로그 정보만으로 확정하기 어려워요.");
        return new ChatResponse(text.toString(), budget.usage(), true, List.of(current.id()));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChatResponse reply(long userId, List<AiMessage> context, boolean personalized, TurnBudget budget) {
        return reply(userId, context, personalized, budget, RecommendationCriteria.NONE);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChatResponse reply(long userId, List<AiMessage> context, boolean personalized, TurnBudget budget, RecommendationCriteria requestedCriteria) {
        String current = context.get(context.size() - 1).text().strip().toLowerCase(Locale.ROOT);
        if (requestedCriteria.clarification() != null) return new ChatResponse(requestedCriteria.clarification(),
                budget.usage(), false);
        // Freeze alias-based exclusions as trusted IDs for retrieval, tools and final validation.
        var resolvedCriteria = retriever.withReviewedAliasExclusions(requestedCriteria);
        final var criteria = resolvedCriteria == null ? requestedCriteria : resolvedCriteria;
        var taste = personalized ? interactions.taste(userId) : null;
        var retrieved = (taste == null ? criteria.active() ? retriever.retrieve(context, criteria) : retriever.retrieve(context)
                : criteria.active() ? retriever.retrievePersonalized(userId, context, taste, criteria) : retriever.retrievePersonalized(userId, context, taste))
                .stream().filter(criteria::matches).toList();
        var enforced = criteria.shortestKnown(retrieved);
        var candidates = retrieved.stream().filter(enforced::matches).toList();
        String noMatch = criteria.active() ? criteria.noMatch() : taste == null ? NO_MATCH
                : "이미 반응한 작품을 제외한 카탈로그에서 조건에 맞는 새 추천을 찾지 못했어요. 장르나 영화/시리즈 조건을 바꿔 주세요.";
        if (candidates.isEmpty()) return new ChatResponse(noMatch, new ChatResponse.Usage(0, 0, 0), false);
        // Local personalization keeps private taste data off the provider and prevents tool bypasses of ranking/exclusions.
        var session = taste == null ? tools.session(userId, candidates, enforced) : null;
        budget.remainingTime();
        var selection = taste == null ? client.recommendWithTools(context, session, budget)
                : new CatalogSelection(candidates.stream().limit(3).map(CatalogCandidate::id).toList(), new ChatResponse.Usage(0, 0, 0));
        var ids = selection.contentIds();
        var allowed = (session == null ? candidates : session.candidates()).stream().map(CatalogCandidate::id).collect(Collectors.toSet());
        if (ids == null || ids.size() > 3 || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> id == null || !allowed.contains(id))) throw ChatException.invalidResponse();
        if (ids.isEmpty()) return new ChatResponse(noMatch, selection.usage(), false);
        // Constructor snapshots read current DB values even when OSIV already manages the candidate entity.
        // Never render a deleted/outdated candidate or trust model-written titles.
        var verified = contents.findByIdIn(ids).stream().map(CatalogCandidate::from)
                .collect(Collectors.toMap(CatalogCandidate::id, Function.identity()));
        if (!verified.keySet().containsAll(ids)) throw ChatException.invalidResponse();
        if (verified.values().stream().anyMatch(work -> !enforced.matches(work))) throw ChatException.invalidResponse();
        if (enforced != criteria && ids.stream().anyMatch(id -> candidates.stream().filter(c -> c.id() == id).noneMatch(c ->
                Objects.equals(c.runtimeMinutes(), verified.get(id).runtimeMinutes())
                        && Objects.equals(c.episodeRuntimeMinutes(), verified.get(id).episodeRuntimeMinutes())))) throw ChatException.invalidResponse();
        if (taste == null) {
            if (!selection.reasons().keySet().equals(new HashSet<>(ids))) throw ChatException.invalidResponse();
            ids.forEach(id -> {
                var reason = selection.reasons().get(id);
                reason.validate(verified.get(id));
                criteria.validateTopicEvidence(reason);
            });
            if (criteria.subjective() && ids.stream().anyMatch(id -> selection.reasons().get(id).evidence().isEmpty())) throw ChatException.invalidResponse();
        }
        // Display requested states deterministically even if the model skipped the reaction tool.
        // This final local read is not sent to the provider and contains at most three verified works.
        boolean showReactions = ReactionSummary.requested(current);
        var reactions = showReactions
                ? interactions.read(userId, new LinkedHashSet<>(ids)) : null;
        if (showReactions && (reactions == null || !reactions.keySet().containsAll(ids))) throw ChatException.invalidResponse();
        var text = new StringBuilder("현재 카탈로그에서 찾은 추천 작품이에요.");
        if (criteria.active()) text.append("\n").append(criteria.summary());
        if (taste != null) text.append("\n").append(taste.summary());
        for (Long id : ids) {
            var c = verified.get(id);
            text.append("\n\n").append(c.title()).append(" (ID: ").append(c.id()).append(")")
                    .append("\n").append(c.type().equalsIgnoreCase("MOVIE") ? "영화" : "시리즈");
            if (!c.releaseDate().isEmpty()) text.append(" · ").append(c.releaseDate());
            if (!c.genres().isBlank()) text.append(" · ").append(c.genres());
            if (c.type().equalsIgnoreCase("MOVIE") && c.runtimeMinutes() != null) text.append("\n러닝타임: ").append(c.runtimeMinutes()).append("분");
            if (c.type().equalsIgnoreCase("TV") && c.episodeRuntimeMinutes() != null) text.append("\n회당 러닝타임(대표값): ").append(c.episodeRuntimeMinutes()).append("분 · 회차별 차이가 있을 수 있어요.");
            if (reactions != null) text.append("\n").append(ReactionSummary.render(reactions.get(id)));
            if (taste != null) text.append("\n").append(taste.reason(c.genres()));
            else {
                var reason = selection.reasons().get(id);
                if (reason.evidence().isEmpty()) text.append("\n추천 이유: 이 작품은 구체적인 카탈로그 근거가 부족해 적합성을 충분히 설명하기 어려워요.");
                else {
                    for (var evidence : reason.evidence()) text.append("\n카탈로그 근거: ").append(evidence.quote());
                    // Do not re-persist private reaction/taste claims in free-form provider text.
                    if (!showReactions && !session.privateDataRead()) text.append("\n추천 이유(모델의 해석): ").append(reason.interpretation());
                }
            }
            if (!c.overview().isBlank()) text.append("\n카탈로그 줄거리: ").append(c.overview());
        }
        var provenance = new com.example.recommend.chat.domain.RecommendationProvenance(context.get(context.size() - 1).text(),
                taste == null ? null : taste.weights().values().stream().anyMatch(w -> w > 0),
                ids.stream().map(id -> taste == null
                        ? new com.example.recommend.chat.domain.RecommendationProvenance.Basis(id, null, List.of())
                        : taste.basis(id, verified.get(id).genres())).toList());
        return new ChatResponse(text.toString(), selection.usage(), true, ids, provenance);
    }
}
