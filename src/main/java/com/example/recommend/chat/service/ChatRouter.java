package com.example.recommend.chat.service;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.config.AiProperties;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.domain.InteractionType;
import com.example.recommend.chat.domain.RecommendationProvenance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ChatRouter {
    private final AiClient client;
    private final CatalogRecommendations recommendations;
    private final ChatInteractionReader interactions;
    private final CatalogDetails details;
    private final AiProperties properties;
    private static final String CLARIFY = "대상의 정확한 제목·카탈로그 ID 또는 최근 답변의 작품 순서를 알려 주세요. "
            + "한 번에 최대 두 가지 요청을 처리할 수 있어요. 반응 목록의 작품은 제목이나 ID를 직접 지정해 주세요.";

    public ChatResponse reply(long userId, List<AiMessage> context) {
        return reply(userId, context, Map.of());
    }

    public ChatResponse reply(long userId, List<AiMessage> context, Map<Integer, RecommendationProvenance> provenance) {
        var budget = new TurnBudget(properties);
        var plan = client.classify(context, budget);
        if (plan == null) throw ChatException.invalidResponse();
        String current = context.get(context.size() - 1).text();
        budget.remainingTime();
        validateSpans(plan, current);
        if (plan.clarification() != null) {
            plan.clarification().validate(current);
            return withUsage(answer(switch (plan.clarification().reason()) {
                case TV_DURATION_UNIT -> "회당 길이가 짧은 시리즈를 찾으시나요, 전체 시청 시간이 짧은 시리즈를 찾으시나요? "
                        + "카탈로그에서는 대표 회당 길이를 확인할 수 있지만, 전체 시청 시간은 확인할 수 없어요.";
                case TITLE_BOUNDARY -> TitleBoundary.QUESTION;
                case QUALITATIVE_COMPARISON -> "연출·연기·작품성의 우열을 판단할 검증된 평가 자료가 없어 어느 작품이 더 뛰어나다고 답할 수 없어요. "
                        + "카탈로그에 저장된 장르·유형·공개일·줄거리를 나란히 비교하거나, 작품을 특정하지 않는 일반적인 연출 평가 기준을 설명할 수 있어요.";
            }), budget);
        }
        // Validate every span and resolve every reference before starting any operation.
        var resolved = new ArrayList<ChatIntent>();
        var rationaleTargets = new HashMap<Integer, Reference>();
        for (var intent : plan.requests()) {
            String question = intent.question() == null ? current : intent.question();
            if (!current.contains(question)) throw ChatException.invalidResponse();
            if (intent.referenceTurn() != null) {
                var target = referencedWork(context, provenance, intent.referenceTurn(), intent.referencePosition(),
                        intent.route() == ChatIntent.Route.RATIONALE);
                if (target == null) return withUsage(answer(CLARIFY), budget);
                if (intent.route() == ChatIntent.Route.RATIONALE) {
                    if (target.request() == null) return withUsage(answer(CLARIFY), budget);
                    rationaleTargets.put(resolved.size(), target);
                    resolved.add(new ChatIntent(intent.route(), intent.media(), null, null, null,
                            intent.referenceTurn(), intent.referencePosition(), question));
                    continue;
                }
                // Owner-checked server metadata, never an ID extracted from model/user answer text.
                resolved.add(new ChatIntent(ChatIntent.Route.DETAIL, ChatIntent.Media.ANY, null, target.ids().get(0), null,
                        null, null, "ID " + target.ids().get(0)));
            } else resolved.add(new ChatIntent(intent.route(), intent.media(), intent.title(), intent.contentId(),
                    intent.releaseYear(), null, null, question));
        }
        // Validate all semantic criteria before any branch can read private data or start work.
        var criteria = RecommendationCriteria.NONE;
        for (var intent : resolved) if (intent.route() == ChatIntent.Route.RECOMMEND || intent.route() == ChatIntent.Route.PERSONALIZED) {
            criteria = RecommendationCriteria.from(plan.constraints(), context, intent.question());
            if (criteria.clarification() != null) return withUsage(answer(criteria.clarification()), budget);
        }
        // Resolve details first; do not proceed with other requests against an absent/ambiguous work.
        var detailAnswers = new HashMap<Integer, ChatResponse>();
        for (int i = 0; i < resolved.size(); i++) {
            var intent = resolved.get(i);
            if (intent.route() != ChatIntent.Route.DETAIL) continue;
            var result = details.reply(intent, intent.question(), budget);
            detailAnswers.put(i, result);
            if (resolved.size() > 1 && result.contentIds().size() != 1)
                return withUsage(new ChatResponse(result.message() + "\n\n작품을 먼저 확정한 뒤 나머지 요청을 다시 말씀해 주세요.",
                        budget.usage(), result.catalogGrounded(), result.contentIds()), budget);
        }
        if (plan.comparison() && detailAnswers.get(0).contentIds().equals(detailAnswers.get(1).contentIds()))
            return withUsage(answer("같은 작품을 두 번 지정했어요. 비교할 서로 다른 두 작품의 제목이나 ID를 알려 주세요."), budget);

        var answers = new ArrayList<ChatResponse>();
        boolean privateList = false;
        for (int i = 0; i < resolved.size(); i++) {
            var intent = resolved.get(i);
            privateList |= intent.route().name().startsWith("LIST_");
            var operationContext = new ArrayList<>(context);
            operationContext.set(context.size() - 1, new AiMessage(AiMessage.Role.USER, intent.question()));
            if (intent.route() == ChatIntent.Route.RATIONALE) {
                var target = rationaleTargets.get(i);
                var response = target.ids().size() == 1
                        ? recommendations.explainRecommendation(target.ids().get(0), target.request(), intent.question(), budget)
                        : recommendations.explainRecommendations(target.ids(), target.request(), intent.question(), budget);
                answers.add(withProvenance(response, target.provenance()));
            } else answers.add(intent.route() == ChatIntent.Route.DETAIL ? detailAnswers.get(i)
                    : dispatch(userId, intent, List.copyOf(operationContext), budget, criteria));
            budget.remainingTime();
        }
        // Details are resolved before generation in compound requests. Recheck their
        // rendered public facts after that generation, before the whole turn is saved.
        if (!detailAnswers.isEmpty() && resolved.stream().anyMatch(intent -> switch (intent.route()) {
            case RECOMMEND, PERSONALIZED, EXPLAIN, RATIONALE -> true;
            default -> false;
        })) {
            for (var entry : detailAnswers.entrySet()) {
                var intent = resolved.get(entry.getKey());
                var currentDetail = details.reply(intent, intent.question(), budget);
                var originalDetail = entry.getValue();
                if (!currentDetail.message().equals(originalDetail.message())
                        || currentDetail.catalogGrounded() != originalDetail.catalogGrounded()
                        || !currentDetail.contentIds().equals(originalDetail.contentIds())) throw ChatException.invalidResponse();
            }
        }
        if (answers.size() == 1) return withUsage(answers.get(0), budget);
        String prefix = privateList ? ChatInteractionReader.LIST_PREFIX + "복합 요청\n\n" : "";
        if (plan.comparison()) prefix += "두 작품의 카탈로그 정보를 나란히 비교해 드릴게요. 저장된 정보만 사용하며 해석이나 우열은 판단하지 않아요.\n\n";
        String text = prefix + "[1] " + answers.get(0).message() + "\n\n[2] " + answers.get(1).message();
        var ids = privateList ? List.<Long>of() : answers.stream().flatMap(a -> a.contentIds().stream()).distinct().limit(5).toList();
        return withUsage(new ChatResponse(text, budget.usage(), answers.stream().allMatch(ChatResponse::catalogGrounded), ids), budget);
    }

    private ChatResponse dispatch(long userId, ChatIntent intent, List<AiMessage> context, TurnBudget budget, RecommendationCriteria criteria) {
        return switch (intent.route()) {
            case RECOMMEND, PERSONALIZED -> recommendations.reply(userId, context,
                    intent.route() == ChatIntent.Route.PERSONALIZED, budget, criteria);
            case LIST_LIKE, LIST_DISLIKE, LIST_BOOKMARK -> new ChatResponse(
                    interactions.list(userId, InteractionType.valueOf(intent.route().name().substring(5)), intent.media()),
                    budget.usage(), true);
            case EXPLAIN -> new ChatResponse("영화·시리즈 일반 설명이에요. 카탈로그 정보나 최신 사실을 확인한 답변은 아니에요.\n\n"
                    + client.explain(intent.question(), budget).message(), budget.usage(), false);
            case DENIED -> answer("다른 사용자의 반응 기록은 조회할 수 없어요. 로그인한 본인의 좋아요·싫어요·찜 목록만 확인할 수 있어요.");
            case CLARIFY -> answer(CLARIFY);
            case UNSUPPORTED -> answer("작품 추천, 본인의 좋아요·싫어요·찜 목록, 카탈로그 작품 상세와 영화·시리즈 일반 설명을 지원해요. 반응 변경·삭제나 실시간 뉴스·순위·시청 가능 서비스 조회는 지원하지 않아요.");
            case OUT_OF_SCOPE -> answer("저는 영화·시리즈와 사용자 취향을 돕는 도우미예요. 작품 추천, 본인 반응 목록, 작품 상세나 영화·시리즈 용어를 물어봐 주세요.");
            case GREETING -> answer("안녕하세요! 영화·시리즈 추천, 본인 반응 목록, 카탈로그 작품 상세와 영화·시리즈 일반 설명을 도와드릴게요.");
            case DETAIL, RATIONALE -> throw ChatException.invalidResponse();
        };
    }

    private record Reference(List<Long> ids, String request, RecommendationProvenance provenance) { }

    private static Reference referencedWork(List<AiMessage> context, Map<Integer, RecommendationProvenance> provenance,
            int turn, int position, boolean allowGroup) {
        int seen = 0;
        for (int i = context.size() - 2; i >= 0; i--) {
            var message = context.get(i);
            if (message.role() != AiMessage.Role.ASSISTANT || ++seen != turn) continue;
            var ids = message.contentIds();
            if (ids.isEmpty() || (position == 0 && ids.size() != 1 && !allowGroup)) return null;
            int index = position == 0 ? 0 : position - 1;
            if (index >= ids.size()) return null;
            String request = i > 0 && context.get(i - 1).role() == AiMessage.Role.USER ? context.get(i - 1).text() : null;
            var selected = position == 0 && allowGroup ? List.copyOf(ids) : List.of(ids.get(index));
            var saved = provenance.get(i);
            if (saved != null) {
                if (!saved.ids().equals(ids)) throw ChatException.invalidResponse();
                saved = saved.select(selected); request = saved.request();
            }
            return new Reference(selected, request, saved);
        }
        return null;
    }

    private static void validateSpans(ChatPlan plan, String current) {
        if (plan.requests().size() == 1) {
            var question = plan.requests().get(0).question();
            if (question != null && !question.equals(current)) throw ChatException.invalidResponse();
            return;
        }
        // No non-whitespace characters may disappear. Comparisons use the complete question for both locators.
        if (plan.comparison()) {
            if (plan.requests().stream().anyMatch(r -> r.question() != null && !r.question().equals(current)))
                throw ChatException.invalidResponse();
            return;
        }
        int end = 0;
        for (var request : plan.requests()) {
            if (request.question() == null) throw ChatException.invalidResponse();
            int start = current.indexOf(request.question(), end);
            if (start < 0 || !current.substring(end, start).isBlank())
                throw ChatException.invalidResponse();
            end = start + request.question().length();
        }
        if (!current.substring(end).isBlank()) throw ChatException.invalidResponse();
    }

    private static ChatResponse withUsage(ChatResponse response, TurnBudget budget) {
        budget.remainingTime();
        return new ChatResponse(response.message(), budget.usage(), response.catalogGrounded(), response.contentIds(), response.provenance());
    }
    private static ChatResponse withProvenance(ChatResponse response, RecommendationProvenance saved) {
        if (saved == null || !response.contentIds().equals(saved.ids())) return response;
        String text = response.message();
        if (saved.positiveHistory() != null) {
            var local = new StringBuilder(text).append("\n\n아래 개인화 기준은 최초 추천 당시 기록이며, 현재 반응과 다를 수 있어요.\n")
                    .append(ChatTaste.summary(saved.positiveHistory()));
            for (var work : saved.works()) local.append("\n\n당시 선정 기준 · ID: ").append(work.contentId())
                    .append("\n").append(ChatTaste.reason(work.score(), work.positiveGenres()));
            text = local.toString();
        }
        return new ChatResponse(text, response.usage(), response.catalogGrounded(), response.contentIds(), saved);
    }
    private static ChatResponse answer(String text) { return new ChatResponse(text, new ChatResponse.Usage(0, 0, 0), false); }
}
