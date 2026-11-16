package com.example.recommend.admin.service;

import com.example.recommend.repository.RecommendLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RecommendLogCleanupService {
    private final RecommendLogRepository logs;

    public record Result(LocalDateTime cutoff, int candidateCount, int deletedCount, boolean dryRun) {}

    @Transactional
    public Result cleanup(int retentionDays, int limit, boolean dryRun) {
        if (retentionDays < 30 || retentionDays > 3650) {
            throw new IllegalArgumentException("retentionDays must be between 30 and 3650");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        var ids = logs.findCleanupCandidates(cutoff, PageRequest.of(0, limit));
        int deleted = 0;
        if (!dryRun) {
            // Tracking takes this same lock. Never acquire parent locks after it.
            for (Long id : ids) {
                if (logs.findLockedById(id).isPresent()) {
                    deleted += logs.deleteCleanupCandidate(id, cutoff);
                }
            }
        }
        return new Result(cutoff, ids.size(), deleted, dryRun);
    }
}
