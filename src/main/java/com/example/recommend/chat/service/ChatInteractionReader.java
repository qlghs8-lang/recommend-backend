package com.example.recommend.chat.service;

import com.example.recommend.domain.InteractionType;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Owner-scoped reaction projections; taste stays local, tool output retains its separate exposure bound. */
@Service
@RequiredArgsConstructor
public class ChatInteractionReader {
    private final EntityManager em;
    // Stable server-only envelope: redact the ENTIRE answer in future provider context, including titles/IDs.
    static final String LIST_PREFIX = "본인 반응 목록(답변 시점): ";
    public static final int MAX_LIST_RESULTS = 20;

    static String providerHistory(String answer) {
        return answer.startsWith(LIST_PREFIX) ? "본인 반응 목록을 서버에서 표시했어요. 목록 내용은 모델 문맥에서 제외되었어요." : answer;
    }

    @Transactional(readOnly = true)
    public String list(long userId, InteractionType type, com.example.recommend.chat.client.ChatIntent.Media media) {
        if (userId <= 0 || type == null || media == null
                || !List.of(InteractionType.LIKE, InteractionType.DISLIKE, InteractionType.BOOKMARK).contains(type))
            throw ChatException.invalidResponse();
        // Group before limiting: repeated events cannot hide additional distinct works.
        var rows = em.createQuery("""
                select c.id, c.title, c.type, max(u.id) from UserContentInteraction u join u.content c
                where u.user.id = :userId and u.eventType = :type
                and (:media = 'ANY' or upper(c.type) = :media)
                group by c.id, c.title, c.type order by max(u.id) desc, c.id desc
                """, Object[].class).setParameter("userId", userId).setParameter("type", type)
                .setParameter("media", media.name()).setMaxResults(MAX_LIST_RESULTS + 1)
                .setHint("jakarta.persistence.query.timeout", 2000).getResultList();
        String label = type == InteractionType.LIKE ? "좋아요" : type == InteractionType.DISLIKE ? "싫어요" : "찜";
        String scope = media == com.example.recommend.chat.client.ChatIntent.Media.MOVIE ? "영화"
                : media == com.example.recommend.chat.client.ChatIntent.Media.TV ? "시리즈" : "작품";
        var text = new StringBuilder(LIST_PREFIX).append(label).append(" · ").append(scope);
        if (rows.isEmpty()) return text.append("\n현재 저장된 해당 반응 기록이 없어요.").toString();
        text.append("\n최근 등록 순으로 최대 20편을 표시해요. 답변 시점의 기록이에요.");
        rows.stream().limit(MAX_LIST_RESULTS).forEach(row -> text.append("\n\n")
                .append(((String) row[1]).replaceAll("[\\p{Cntrl}]", " "))
                .append(" (ID: ").append(row[0]).append(") · ")
                .append("MOVIE".equalsIgnoreCase((String) row[2]) ? "영화" : "시리즈"));
        text.append(rows.size() > MAX_LIST_RESULTS ? "\n\n표시하지 않은 작품이 더 있어요. 전체 목록이 아니에요."
                : "\n\n현재 조회 조건에 해당하는 기록을 모두 표시했어요.");
        return text.toString();
    }

    @Transactional(readOnly = true)
    public ChatTaste taste(long userId) {
        if (userId <= 0) throw ChatException.invalidResponse();
        // Fetch scalar catalog genres only; VIEW events and other accounts never contribute.
        var rows = em.createQuery("""
                select u.content.id, u.eventType, u.content.genres from UserContentInteraction u
                where u.user.id = :userId and u.eventType in :types
                order by u.createdAt desc, u.id desc
                """, Object[].class).setParameter("userId", userId)
                .setParameter("types", List.of(InteractionType.LIKE, InteractionType.BOOKMARK, InteractionType.DISLIKE))
                .setMaxResults(100).setHint("jakarta.persistence.query.timeout", 2000).getResultList();
        var seen = new HashSet<String>();
        var weights = new TreeMap<String, Integer>();
        for (var row : rows) {
            var type = (InteractionType) row[1];
            if (!seen.add(row[0] + ":" + type)) continue;
            int weight = type == InteractionType.LIKE ? 3 : type == InteractionType.BOOKMARK ? 2 : -3;
            ChatTaste.genres((String) row[2]).forEach(g -> weights.merge(g, weight, Integer::sum));
        }
        // Bound SQL score terms even when catalog genre labels are heterogeneous.
        var bounded = new LinkedHashMap<String, Integer>();
        weights.entrySet().stream().filter(e -> e.getValue() != 0)
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> Math.abs(e.getValue())).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(16).forEach(e -> bounded.put(e.getKey(), e.getValue()));
        return new ChatTaste(bounded);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<String>> read(long userId, Set<Long> ids) {
        if (userId <= 0 || ids == null || ids.isEmpty() || ids.size() > 12
                || ids.stream().anyMatch(id -> id == null || id <= 0)) throw ChatException.invalidResponse();
        var rows = em.createQuery("""
                select distinct u.content.id, u.eventType from UserContentInteraction u
                where u.user.id = :userId and u.content.id in :ids and u.eventType in :types
                """, Object[].class).setParameter("userId", userId).setParameter("ids", ids)
                .setParameter("types", List.of(InteractionType.LIKE, InteractionType.DISLIKE, InteractionType.BOOKMARK))
                .setMaxResults(36).setHint("jakarta.persistence.query.timeout", 2000).getResultList();
        var result = new LinkedHashMap<Long, List<String>>();
        ids.forEach(id -> result.put(id, new ArrayList<>()));
        rows.forEach(row -> result.get((Long) row[0]).add(((InteractionType) row[1]).name()));
        result.values().forEach(Collections::sort);
        return result;
    }
}
