package com.positivity.poseventreceiver.internal.service;

import com.positivity.poseventreceiver.internal.dto.EventSummaryResponse;
import com.positivity.poseventreceiver.internal.exception.TenantScopeForbiddenException;
import com.positivity.poseventreceiver.internal.repository.EmittedEventHourlyRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Reads the {@code emitted_event_hourly} continuous aggregate with the tenant dimension of ADR-0062
 * plan WS6: bound-tenant callers get their own rows; the platform tenant gets the global rollup, or
 * one named tenant. The aggregate has no row-level security, so the scope chosen here is the
 * isolation.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class EventSummaryServiceImpl implements EventSummaryService {

    private final EmittedEventHourlyRepository emittedEventHourlyRepository;
    private final Clock clock;
    private final TenantResolver tenantResolver;

    @Override
    public @NonNull List<EventSummaryResponse> getLastHourSummary(@Nullable UUID requestedTenantId) {
        log.info("Fetching event summary for the last hour");
        return getSummary(Duration.ofHours(1), requestedTenantId);
    }

    @Override
    public @NonNull List<EventSummaryResponse> getLastDaySummary(@Nullable UUID requestedTenantId) {
        log.info("Fetching event summary for the last day");
        return getSummary(Duration.ofDays(1), requestedTenantId);
    }

    @Override
    public @NonNull List<EventSummaryResponse> getLastWeekSummary(@Nullable UUID requestedTenantId) {
        log.info("Fetching event summary for the last week");
        return getSummary(Duration.ofDays(7), requestedTenantId);
    }

    private List<EventSummaryResponse> getSummary(Duration window, @Nullable UUID requestedTenantId) {
        Instant since = Instant.now(clock).minus(window);
        return summarize(since, requestedTenantId).stream()
                .map(row -> new EventSummaryResponse((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    /**
     * The scope rule: an explicit tenant is a platform-tenant parameter (403 otherwise); with none
     * named, the platform tenant reads the global rollup and every other tenant reads itself.
     */
    private List<Object[]> summarize(Instant since, @Nullable UUID requestedTenantId) {
        UUID bound = tenantResolver.require();
        boolean platform = PlatformTenant.isPlatform(bound);
        if (requestedTenantId != null) {
            if (!platform) {
                log.warn("Tenant {} asked for the event summary of tenant {}: refused", bound, requestedTenantId);
                throw new TenantScopeForbiddenException();
            }
            log.debug("Event summary scope: tenant {} (named by the platform tenant)", requestedTenantId);
            return emittedEventHourlyRepository.summarizeSince(requestedTenantId, since);
        }
        if (platform) {
            log.debug("Event summary scope: global rollup across tenants");
            return emittedEventHourlyRepository.summarizeAcrossTenantsSince(since);
        }
        log.debug("Event summary scope: bound tenant {}", bound);
        return emittedEventHourlyRepository.summarizeSince(bound, since);
    }
}
