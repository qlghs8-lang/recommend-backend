package com.example.recommend.service;

import com.example.recommend.domain.*;
import com.example.recommend.repository.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendServiceTest {
    private final ContentRepository contents = mock(ContentRepository.class);
    private final UserContentInteractionRepository interactions = mock(UserContentInteractionRepository.class);
    private final RecommendService service = new RecommendService(contents, interactions,
            mock(RecommendLogRepository.class), mock(RecommendClickLogRepository.class), mock(UserRepository.class));
    private final User user = User.builder().id(1L).build();
    private final List<InteractionType> positive = List.of(InteractionType.LIKE, InteractionType.BOOKMARK);

    private Content content(long id, String genres, Long views) {
        return Content.builder().id(id).genres(genres).viewCount(views).build();
    }

    private void catalog(Content... items) {
        when(contents.findAll()).thenReturn(List.of(items));
        when(contents.findByIdIn(any())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return List.of(items).stream().filter(c -> ids.contains(c.getId())).toList();
        });
    }

    @Test void coldStartExcludesDislikesEvenWithoutPreferredGenres() {
        Content disliked = content(2, "action", 100L);
        Content allowed = content(3, "drama", 0L);
        catalog(disliked, allowed);
        when(interactions.findContentIdsByUserAndTypes(1L, List.of(InteractionType.DISLIKE)))
                .thenReturn(List.of(2L));
        assertEquals(List.of(allowed), service.forYou(user, 10));
    }

    @Test void onboardingStillRanksMatchingGenreWhenAllViewsAreZero() {
        user.setPreferredGenres("action");
        Content other = content(2, "drama", 0L);
        Content matching = content(3, "action", 0L);
        catalog(other, matching);
        assertEquals(List.of(matching), service.forYou(user, 1));
    }

    @Test void warmStartStillRanksMatchingGenreWhenViewsAreZeroOrNull() {
        Content anchor = content(2, "action", null);
        Content other = content(3, "drama", 0L);
        Content matching = content(4, "action", 0L);
        catalog(anchor, other, matching);
        when(interactions.findContentIdsByUserAndTypes(1L, positive)).thenReturn(List.of(2L));
        when(interactions.findContentIdsByUserAndTypes(1L,
                List.of(InteractionType.LIKE, InteractionType.DISLIKE, InteractionType.BOOKMARK)))
                .thenReturn(List.of(2L));
        assertEquals(List.of(matching), service.forYou(user, 1));
        verify(contents, times(1)).findAll();
    }

    @Test void collaborativeSupportOutranksViewsAndDislikesRemainExcluded() {
        Content anchor = content(2, "", 0L);
        Content popular = content(3, "", 1000L);
        Content supported = content(4, "", 0L);
        Content disliked = content(5, "", 2000L);
        catalog(anchor, popular, supported, disliked);
        when(interactions.findContentIdsByUserAndTypes(1L, positive)).thenReturn(List.of(2L));
        when(interactions.findContentIdsByUserAndTypes(1L, List.of(InteractionType.DISLIKE)))
                .thenReturn(List.of(5L));
        when(interactions.findOtherUserIdsWhoInteracted(1L, List.of(2L), positive)).thenReturn(List.of(10L, 11L));
        when(interactions.countSupportersByContent(List.of(10L, 11L), positive))
                .thenReturn(List.of(support(3L, 1L), support(4L, 10L), support(5L, 20L)));
        assertEquals(List.of(supported, popular), service.forYou(user, 2));
    }

    private UserContentInteractionRepository.ContentSupport support(Long id, Long count) {
        return new UserContentInteractionRepository.ContentSupport() {
            public Long getContentId() { return id; }
            public Long getSupporterCount() { return count; }
        };
    }
}
