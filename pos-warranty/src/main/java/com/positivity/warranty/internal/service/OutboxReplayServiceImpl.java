package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.repository.OutboxEventRepository;
import com.positivity.warranty.service.OutboxReplayService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxReplayServiceImpl implements OutboxReplayService {

    private final OutboxEventRepository outboxEventRepository;

    @Override
    @Transactional
    public int replaySince(@NonNull Instant since) {
        int count = outboxEventRepository.markForReplaySince(since);
        log.info("Warranty outbox replay requested since={} eventsQueued={}", since, count);
        return count;
    }

    @Override
    @Transactional
    public int replayBetween(@NonNull Instant since, @NonNull Instant until) {
        int count = outboxEventRepository.markForReplayBetween(since, until);
        log.info("Warranty outbox replay requested window=[{}, {}) eventsQueued={}", since, until, count);
        return count;
    }
}
