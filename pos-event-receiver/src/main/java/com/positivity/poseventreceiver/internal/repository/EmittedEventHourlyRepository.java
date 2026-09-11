package com.positivity.poseventreceiver.internal.repository;

import com.positivity.poseventreceiver.internal.entity.EmittedEventHourly;
import com.positivity.poseventreceiver.internal.entity.EmittedEventHourlyId;
import com.positivity.tenancy.TenantAudited;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Reads over the {@code emitted_event_hourly} continuous aggregate (ADR-0062 plan WS6).
 *
 * <p>Deliberately not a {@code JpaRepository}: the aggregate has no row-level security, so an
 * inherited {@code findAll()} would read across tenants. The per-tenant query names the tenant
 * column; the one cross-tenant rollup is explicit, reviewed, and reached from the platform
 * tenant only ({@code EventSummaryServiceImpl}).
 */
public interface EmittedEventHourlyRepository extends Repository<EmittedEventHourly, EmittedEventHourlyId> {

    /**
     * Hourly counts of one tenant's events since {@code since}, summed per event type. The tenant
     * predicate is the isolation: the aggregate has no row-level security (see
     * {@link EmittedEventHourly}).
     */
    @Query("""
      SELECT h.eventType, SUM(h.eventCount)
      FROM EmittedEventHourly h
      WHERE h.tenantId = :tenantId AND h.bucket >= :since
      GROUP BY h.eventType
      ORDER BY SUM(h.eventCount) DESC
      """)
    @NonNull
    List<Object[]> summarizeSince(@Param("tenantId") @NonNull UUID tenantId, @Param("since") @NonNull Instant since);

    /**
     * The global rollup: hourly counts of every tenant's events since {@code since}, summed per
     * event type across tenants. Platform-tenant callers only.
     */
    @TenantAudited(
            reason = "the global rollup of plan WS6 sums every tenant's hourly buckets on purpose; the service"
                    + " reaches it from the platform tenant only, and the aggregate has no row-level security"
                    + " for it to bypass")
    @Query("""
      SELECT h.eventType, SUM(h.eventCount)
      FROM EmittedEventHourly h
      WHERE h.bucket >= :since
      GROUP BY h.eventType
      ORDER BY SUM(h.eventCount) DESC
      """)
    @NonNull
    List<Object[]> summarizeAcrossTenantsSince(@Param("since") @NonNull Instant since);
}
