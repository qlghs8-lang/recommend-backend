package com.example.recommend.chat.service;

import com.example.recommend.chat.client.AiMessage;
import com.example.recommend.chat.domain.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.dto.ConversationDtos.*;
import com.example.recommend.chat.repository.*;
import com.example.recommend.repository.UserRepository;
import com.example.recommend.repository.ContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatHistoryStore {
    public static final int MAX_CONTEXT_MESSAGES = 12;
    public static final int MAX_CONTEXT_CHARS = 12000;
    public static final int MAX_ROOM_MESSAGES = 200;
    private final ChatRoomRepository rooms;
    private final ChatMessageRepository messages;
    private final UserRepository users;
    private final ContentRepository contents;

    public record Snapshot(int messageCount, List<AiMessage> context, Map<Integer, RecommendationProvenance> provenance) {
        public Snapshot(int messageCount, List<AiMessage> context) { this(messageCount, context, Map.of()); }
        public Snapshot { context = List.copyOf(context); provenance = Map.copyOf(provenance); }
    }

    @Transactional
    public Room create(long userId) {
        // Match account-deletion lock ordering; serialize the per-user room cap.
        var user = users.findByIdForUpdate(userId).orElseThrow(ChatException::notFound);
        if (rooms.countByUserId(userId) >= 100) throw ChatException.limitReached();
        return Room.from(rooms.save(new ChatRoom(user)));
    }

    public Rooms list(long userId, Long before) {
        var found = rooms.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor(before), PageRequest.of(0, 21));
        var page = found.stream().limit(20).map(Room::from).toList();
        boolean more = found.size() > 20;
        return new Rooms(page, more, more ? page.get(page.size() - 1).id() : null);
    }

    public History history(long userId, long roomId, Long before) {
        var room = owned(userId, roomId);
        var found = messages.findByRoomIdAndIdLessThanOrderByIdDesc(roomId, cursor(before), PageRequest.of(0, 41));
        var page = new ArrayList<>(publicMessages(found.stream().limit(40).toList()));
        Collections.reverse(page);
        boolean more = found.size() > 40;
        return new History(Room.from(room), List.copyOf(page), more, more ? page.get(0).id() : null);
    }

    public Snapshot prepare(long userId, long roomId, String question) {
        var room = owned(userId, roomId);
        if (room.getMessageCount() >= MAX_ROOM_MESSAGES) throw ChatException.limitReached();
        var recent = messages.findByRoomIdAndIdLessThanOrderByIdDesc(roomId, Long.MAX_VALUE,
                PageRequest.of(0, MAX_CONTEXT_MESSAGES));
        var context = new ArrayList<AiMessage>();
        var provenance = new HashMap<Integer, RecommendationProvenance>();
        int chars = question.length();
        // Include complete turns only, newest first while selecting, chronological on output.
        for (int i = 0; i + 1 < recent.size(); i += 2) {
            var answer = recent.get(i);
            var prompt = recent.get(i + 1);
            if (answer.getRole() != ChatMessage.Role.ASSISTANT || prompt.getRole() != ChatMessage.Role.USER)
                throw ChatException.conflict();
            if (chars + answer.getText().length() + prompt.getText().length() > MAX_CONTEXT_CHARS) break;
            chars += answer.getText().length() + prompt.getText().length();
            if (!answer.getText().startsWith(ChatInteractionReader.LIST_PREFIX)) {
                var saved = answer.provenance();
                if (saved != null) provenance.put(context.size(), saved);
            }
            // Keep private reaction/taste snapshots in UI history only, not future provider input.
            context.add(new AiMessage(AiMessage.Role.ASSISTANT,
                    ChatTaste.withoutSnapshots(ReactionSummary.withoutSnapshots(ChatInteractionReader.providerHistory(answer.getText()))),
                    answer.getText().startsWith(ChatInteractionReader.LIST_PREFIX) ? List.of() : answer.referencedIds()));
            context.add(new AiMessage(AiMessage.Role.USER, prompt.getText()));
        }
        Collections.reverse(context);
        var chronological = new HashMap<Integer, RecommendationProvenance>();
        int size = context.size();
        provenance.forEach((index, saved) -> chronological.put(size - 1 - index, saved));
        context.add(new AiMessage(AiMessage.Role.USER, question));
        return new Snapshot(room.getMessageCount(), context, chronological);
    }

    @Transactional
    public Turn complete(long userId, long roomId, Snapshot snapshot, String question, ChatResponse response) {
        var room = rooms.findOwnedForUpdate(roomId, userId).orElseThrow(ChatException::notFound);
        if (room.getMessageCount() != snapshot.messageCount()) throw ChatException.conflict();
        var prompt = messages.save(new ChatMessage(room, ChatMessage.Role.USER, question));
        var answer = messages.save(new ChatMessage(room, response.message(), response.contentIds(), response.provenance()));
        room.recordTurn(question);
        return new Turn(Room.from(room), publicMessages(List.of(prompt, answer)), response.usage(), response.catalogGrounded());
    }

    @Transactional
    public void delete(long userId, long roomId) {
        var room = rooms.findOwnedForUpdate(roomId, userId).orElseThrow(ChatException::notFound);
        messages.deleteForRoom(roomId);
        rooms.delete(room);
    }

    private ChatRoom owned(long userId, long roomId) {
        return rooms.findByIdAndUserId(roomId, userId).orElseThrow(ChatException::notFound);
    }
    private List<Message> publicMessages(List<ChatMessage> page) {
        // Ownership is checked before this lookup. Use only persisted server-validated
        // references, never answer text, provenance, reaction anchors or client IDs.
        var ids = page.stream().flatMap(m -> m.referencedIds().stream()).distinct().toList();
        var links = new HashMap<Long, ContentLink>();
        if (!ids.isEmpty()) contents.findByIdIn(ids).forEach(c -> {
            if (c.getTitle() != null && !c.getTitle().isBlank())
                links.put(c.getId(), new ContentLink(c.getId(), c.getTitle(), c.getType(), c.getPosterUrl()));
        });
        // One bounded catalog lookup for the page (at most 40 messages * 5 IDs).
        // Deleted records disappear from cards while the original text is preserved.
        return page.stream().map(m -> Message.from(m, m.referencedIds().stream()
                .map(links::get).filter(Objects::nonNull).toList())).toList();
    }
    private long cursor(Long before) {
        if (before != null && before < 1) throw new IllegalArgumentException("Invalid cursor");
        return before == null ? Long.MAX_VALUE : before;
    }
}
