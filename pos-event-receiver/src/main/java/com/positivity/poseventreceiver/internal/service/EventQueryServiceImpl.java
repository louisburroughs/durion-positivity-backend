package com.positivity.poseventreceiver.internal.service;

import com.positivity.poseventreceiver.internal.dto.EmittedEventResponse;
import com.positivity.poseventreceiver.internal.dto.PagedResponse;
import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import com.positivity.poseventreceiver.internal.repository.EmittedEventRepository;
import com.positivity.poseventreceiver.service.EventQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class EventQueryServiceImpl implements EventQueryService {

    /** Default lookback applied when {@code since} is not supplied. */
    static final Duration DEFAULT_LOOKBACK = Duration.ofDays(7);

    /** Furthest back a resolved {@code since} may reach. */
    static final Duration MAX_LOOKBACK = Duration.ofDays(90);

    private final EmittedEventRepository emittedEventRepository;
    private final Clock clock;

    @Override
    public @NonNull PagedResponse<EmittedEventResponse> findByEntity(
            @NonNull String entityId, @Nullable Instant since, int page, int size) {
        Instant now = Instant.now(clock);
        Instant resolvedSince = since != null ? since : now.minus(DEFAULT_LOOKBACK);

        if (resolvedSince.isAfter(now)) {
            throw new IllegalArgumentException("since must not be in the future");
        }
        if (resolvedSince.isBefore(now.minus(MAX_LOOKBACK))) {
            throw new IllegalArgumentException("since must not be more than 90 days in the past");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt"));
        Page<EmittedEvent> result =
                emittedEventRepository.findByEntityIdAndPublishedAtGreaterThanEqual(entityId, resolvedSince, pageable);
        log.debug(
                "Queried events for entityId(mask)={}, since={}, page={}, size={}: {} of {} total",
                maskForLog(entityId),
                resolvedSince,
                page,
                size,
                result.getNumberOfElements(),
                result.getTotalElements());

        return new PagedResponse<>(
                result.getContent().stream()
                        .map(EventQueryServiceImpl::toResponse)
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements());
    }

    private static EmittedEventResponse toResponse(EmittedEvent event) {
        return new EmittedEventResponse(
                event.getEventId(),
                event.getId(),
                event.getApiVersion(),
                event.getTimestamp(),
                event.getElapsedMs(),
                event.getPublishedAt(),
                event.getEntityId());
    }

    private static String maskForLog(String value) {
        int length = value.length();
        if (length <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "***" + value.substring(length - 2);
    }
}
