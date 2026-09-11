package com.positivity.poseventreceiver.internal.service;

import com.positivity.poseventreceiver.internal.dto.EventSummaryResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Service API for querying aggregated event summaries by timeframe.
 *
 * <p>Tenant dimension with global rollups (ADR-0062 plan WS6): a caller bound to an ordinary tenant
 * sees that tenant's counts and may not name another; a caller bound to the platform tenant sees
 * the global rollup (the sum across tenants) unless it names one tenant with {@code
 * requestedTenantId}.
 */
public interface EventSummaryService {

    /**
     * Returns event counts grouped by event type for the last hour.
     *
     * @param requestedTenantId the tenant to report on, platform-tenant callers only; {@code null}
     *     for the caller's own tenant, or the global rollup when the caller is the platform tenant
     * @throws com.positivity.poseventreceiver.internal.exception.TenantScopeForbiddenException when
     *     a tenant is named by a caller that is not the platform tenant
     */
    @NonNull
    List<EventSummaryResponse> getLastHourSummary(@Nullable UUID requestedTenantId);

    /**
     * Returns event counts grouped by event type for the last day (24 hours).
     *
     * @param requestedTenantId as for {@link #getLastHourSummary(UUID)}
     */
    @NonNull
    List<EventSummaryResponse> getLastDaySummary(@Nullable UUID requestedTenantId);

    /**
     * Returns event counts grouped by event type for the last week (7 days).
     *
     * @param requestedTenantId as for {@link #getLastHourSummary(UUID)}
     */
    @NonNull
    List<EventSummaryResponse> getLastWeekSummary(@Nullable UUID requestedTenantId);
}
