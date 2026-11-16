package com.example.recommend.service;

import com.example.recommend.domain.*;
import com.example.recommend.repository.*;
import com.example.recommend.exception.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationTrackingTest {
    final ContentRepository contents = mock(ContentRepository.class);
    final RecommendLogRepository logs = mock(RecommendLogRepository.class);
    final RecommendClickLogRepository clicks = mock(RecommendClickLogRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final RecommendService service = new RecommendService(contents,
            mock(UserContentInteractionRepository.class), logs, clicks, users);
    final User user = User.builder().id(1L).build();

    @org.junit.jupiter.api.BeforeEach void parents() {
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(contents.findByIdForUpdate(3L)).thenReturn(Optional.of(Content.builder().id(3L).build()));
    }

    @Test void generationCreatesUnexposedCandidatesWithFreshIds() {
        when(contents.findAll()).thenReturn(List.of(Content.builder().id(3L).viewCount(1L).build()));
        when(logs.saveAll(any())).thenAnswer(inv -> {
            List<RecommendLog> candidates = inv.getArgument(0);
            candidates.forEach(log -> { assertNull(log.getExposedAt()); log.setId(10L); });
            return candidates;
        });
        assertEquals(10L, service.forYouWithReasons(user, 12).get(0).recommendLogId());
        verify(logs).saveAll(any());
        verifyNoInteractions(clicks);
    }

    @Test void duplicateImpressionPreservesFirstTimestampAndRepeatedClicksAreStored() {
        RecommendLog log = RecommendLog.builder().id(10L).userId(1L).contentId(3L).build();
        when(logs.findById(10L)).thenReturn(Optional.of(log));
        when(logs.findLockedById(10L)).thenReturn(Optional.of(log));
        service.logImpression(user, 10L);
        LocalDateTime first = log.getExposedAt();
        service.logImpression(user, 10L);
        assertEquals(first, log.getExposedAt());
        verify(logs, times(1)).save(log);
        service.logClick(user, 10L);
        service.logClick(user, 10L);
        verify(clicks, times(2)).save(any());
    }

    @Test void rejectsUnexposedClicksOtherOwnersAndMissingIds() {
        RecommendLog log = RecommendLog.builder().id(10L).userId(1L).contentId(3L).build();
        when(logs.findById(10L)).thenReturn(Optional.of(log));
        when(logs.findLockedById(10L)).thenReturn(Optional.of(log));
        assertThrows(IllegalArgumentException.class, () -> service.logClick(user, 10L));
        log.setUserId(2L);
        assertThrows(ForbiddenException.class, () -> service.logImpression(user, 10L));
        assertThrows(ForbiddenException.class, () -> service.logClick(user, 10L));
        assertThrows(ResourceNotFoundException.class, () -> service.logImpression(user, 20L));
        verifyNoInteractions(clicks);
        verify(logs, never()).save(any());
    }

    @Test void limitsCandidateCountBeforeReadingCatalog() {
        assertThrows(IllegalArgumentException.class, () -> service.forYouWithReasons(user, 101));
        assertThrows(IllegalArgumentException.class, () -> service.forYou(user, 0));
        verifyNoInteractions(contents);
    }
}
