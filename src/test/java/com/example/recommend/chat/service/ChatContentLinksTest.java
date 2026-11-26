package com.example.recommend.chat.service;

import com.example.recommend.chat.domain.*;
import com.example.recommend.chat.dto.ChatResponse;
import com.example.recommend.chat.repository.ChatMessageRepository;
import com.example.recommend.domain.Content;
import com.example.recommend.domain.User;
import com.example.recommend.repository.ContentRepository;
import com.example.recommend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.properties.hibernate.generate_statistics=true"})
@Import(ChatHistoryStore.class)
class ChatContentLinksTest {
    @Autowired ChatHistoryStore history;
    @Autowired UserRepository users;
    @Autowired ContentRepository contents;
    @Autowired ChatMessageRepository messages;
    @Autowired EntityManager em;
    long owner, other, room, first, second;

    @BeforeEach void setup() {
        String suffix = UUID.randomUUID().toString();
        owner = users.save(User.builder().email(suffix+"@example.com").nickname(suffix).password("hash").build()).getId();
        other = users.save(User.builder().email(suffix+"x@example.com").nickname(suffix+"x").password("hash").build()).getId();
        room = history.create(owner).id();
        first = contents.save(Content.builder().type("MOVIE").title("첫 작품").posterUrl("https://image.tmdb.org/t/p/w500/a.jpg").build()).getId();
        second = contents.save(Content.builder().type("TV").title("둘째 작품").build()).getId();
    }
    private com.example.recommend.chat.dto.ConversationDtos.Turn append(String text, List<Long> ids) {
        return history.complete(owner, room, history.prepare(owner,room,"질문"),"질문",
                new ChatResponse(text,new ChatResponse.Usage(1,1,2),!ids.isEmpty(),ids));
    }

    @Test void sendAndReloadExposeOnlyActualPublicCardsInSavedOrder() {
        var basis = new RecommendationProvenance("private original request",true,List.of(
                new RecommendationProvenance.Basis(second,3,List.of("private preference")),
                new RecommendationProvenance.Basis(first,0,List.of())));
        var turn = history.complete(owner,room,history.prepare(owner,room,"질문"),"질문",
                new ChatResponse("검증된 추천",new ChatResponse.Usage(1,1,2),true,List.of(second,first),basis));
        assertTrue(turn.messages().get(0).contents().isEmpty());
        var cards = turn.messages().get(1).contents();
        assertEquals(List.of(second,first),cards.stream().map(c -> c.id()).toList());
        assertEquals(List.of("둘째 작품","첫 작품"),cards.stream().map(c -> c.title()).toList());
        assertEquals(cards,history.history(owner,room,null).messages().get(1).contents());
        var tree = JsonMapper.builder().build().valueToTree(turn.messages().get(1));
        assertEquals(4,tree.get("contents").get(0).size());
        assertFalse(tree.toString().contains("private"));
        assertFalse(tree.has("provenance")); assertFalse(tree.has("contentReferences"));
        assertFalse(tree.has("contentIds"));
        assertThrows(UnsupportedOperationException.class,() -> cards.clear());
    }

    @Test void ownershipIsCheckedAndTextAloneNeverBecomesACard() {
        append("첫 작품 (ID: "+first+")",List.of());
        assertTrue(history.history(owner,room,null).messages().get(1).contents().isEmpty());
        assertEquals(404,assertThrows(ChatException.class,() -> history.history(other,room,null)).status());
    }

    @Test void deletedOrRenamedCatalogItemsAreResolvedAtReadTime() {
        append("이전 제목이 담긴 답변",List.of(first,second));
        var changed = contents.findById(first).orElseThrow(); changed.setTitle("현재 제목");
        contents.deleteById(second); em.flush(); em.clear();
        var answer = history.history(owner,room,null).messages().get(1);
        assertEquals("이전 제목이 담긴 답변",answer.text());
        assertEquals(1,answer.contents().size());
        assertEquals("현재 제목",answer.contents().get(0).title());
    }

    @Test void malformedMissingAndUserReferencesRemainUnlinked() {
        var turn = append("원문 보존",List.of(first));
        var answer = messages.findById(turn.messages().get(1).id()).orElseThrow();
        for (String invalid : List.of(first+","+first,"not-an-id","0",String.valueOf(Long.MAX_VALUE))) {
            ReflectionTestUtils.setField(answer,"contentReferences",invalid); em.flush();
            assertTrue(history.history(owner,room,null).messages().get(1).contents().isEmpty());
        }
        var user = messages.findById(turn.messages().get(0).id()).orElseThrow();
        ReflectionTestUtils.setField(user,"contentReferences",String.valueOf(first)); em.flush();
        assertTrue(history.history(owner,room,null).messages().get(0).contents().isEmpty());
    }

    @Test void oneCatalogLookupServesAFullHistoryPageWithoutChangingItsCursor() {
        for (int i=0;i<21;i++) append("답변 "+i,List.of(first,second));
        em.flush(); em.clear();
        var stats = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.clear();
        var page = history.history(owner,room,null);
        assertEquals(40,page.messages().size()); assertTrue(page.hasMore());
        assertEquals(page.messages().get(0).id(),page.nextBefore());
        assertTrue(page.messages().stream().filter(m -> m.role().equals("assistant")).allMatch(m -> m.contents().size()==2));
        assertEquals(3,stats.getPrepareStatementCount(),"room ownership + history page + one catalog lookup");
    }
}
