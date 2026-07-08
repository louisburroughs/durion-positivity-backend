package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.repository.OutboxEventRepository;
import com.positivity.workorder.service.OutboxReplayService;
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
        log.info("Outbox replay requested since={} eventsQueued={}", since, count);
        return count;
    }
}
