package com.positivity.poseventreceiver.internal.service;

import com.positivity.poseventreceiver.internal.dto.EventSummaryResponse;
import com.positivity.poseventreceiver.internal.repository.EmittedEventHourlyRepository;
import com.positivity.poseventreceiver.service.EventSummaryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class EventSummaryServiceImpl implements EventSummaryService {

    private final EmittedEventHourlyRepository emittedEventHourlyRepository;
    private final Clock clock;

    @Override
    public @NonNull List<EventSummaryResponse> getLastHourSummary() {
        log.info("Fetching event summary for the last hour");
        return getSummary(Duration.ofHours(1));
    }

    @Override
    public @NonNull List<EventSummaryResponse> getLastDaySummary() {
        log.info("Fetching event summary for the last day");
        return getSummary(Duration.ofDays(1));
    }

    @Override
    public @NonNull List<EventSummaryResponse> getLastWeekSummary() {
        log.info("Fetching event summary for the last week");
        return getSummary(Duration.ofDays(7));
    }

    private List<EventSummaryResponse> getSummary(Duration window) {
        Instant since = Instant.now(clock).minus(window);
        List<Object[]> results = emittedEventHourlyRepository.summarizeSince(since);
        return results.stream()
                .map(row -> new EventSummaryResponse((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }
}
