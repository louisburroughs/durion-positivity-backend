package com.positivity.workorder.internal.service;

import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantIterator;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Regenerate-from-state backfill for the workorder actual-time fact fields (issue #2021 AC8).
 *
 * <p>Pages through {@code WorkorderRepository#findActualTimeBackfillPage} rather than loading every
 * candidate row at once: a backfill can run against every started or completed workorder in the
 * module, and materializing all of them in one persistence context is what turns a repair into an
 * outage. Each page is a separate transaction owned by {@link WorkorderFactBackfillPagePublisher} —
 * see that class for why the split is required rather than stylistic.
 *
 * <p>A run stops at {@code maxRowsPerRun} and reports the cursor to resume from, so a large estate is
 * walked over several commands instead of one unbounded pass on the Kafka listener thread. See
 * {@link WorkorderFactBackfillService} for why that bound exists.
 *
 * <p>Tenant-scoped per ADR-0062 §3, mirroring {@code OutboxReplayServiceImpl#replaySinceForCaller}:
 * an ordinary tenant's walk covers only its own rows (Hibernate's {@code @TenantId} on {@code
 * Workorder} adds the predicate to every query), and a platform-tenant operator — who owns no
 * workorder rows — fans out over every active tenant in turn through {@link TenantIterator}, each
 * with its own bounded run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkorderFactBackfillServiceImpl implements WorkorderFactBackfillService {

    private final WorkorderFactBackfillPagePublisher pagePublisher;
    private final TenantIterator tenantIterator;

    /** Rows per transaction. Bounds heap, outbox commit size, and how long a page holds its lock. */
    @Value("${pos.workorder.fact-backfill.page-size:500}")
    private int pageSize;

    /** Rows per command, after which the run reports a resume cursor and stops. */
    @Value("${pos.workorder.fact-backfill.max-rows-per-run:20000}")
    private int maxRowsPerRun;

    /**
     * Rejects a misconfigured bound at startup rather than at command time.
     *
     * <p>Without this, {@code page-size=0} makes {@code PageRequest.ofSize} throw
     * {@code IllegalArgumentException}, which the command listener catches in its generic handler
     * and logs as a malformed command — the operator is told their command is wrong when the fault
     * is in configuration, and the backfill silently never runs.
     */
    @PostConstruct
    void validateConfiguration() {
        if (pageSize < 1) {
            throw new IllegalStateException("pos.workorder.fact-backfill.page-size must be >= 1, was " + pageSize);
        }
        if (maxRowsPerRun < 1) {
            throw new IllegalStateException(
                    "pos.workorder.fact-backfill.max-rows-per-run must be >= 1, was " + maxRowsPerRun);
        }
    }

    @Override
    public BackfillResult backfillForCaller(@Nullable UUID afterId) {
        UUID caller = TenantContext.require();
        if (!PlatformTenant.isPlatform(caller)) {
            BackfillResult result = pageThrough(afterId);
            log.info(
                    "Workorder fact backfill run complete tenant={} published={} lastId={} more={}",
                    caller,
                    result.published(),
                    result.lastId(),
                    result.more());
            return result;
        }
        if (afterId != null) {
            // A cursor cannot span tenants: each has its own independent id space, so honoring the
            // caller's cursor here would resume an arbitrary tenant's walk while restarting every
            // other tenant's from the beginning.
            log.warn(
                    "Ignoring payload.afterId={} on a platform-tenant fact backfill: a cursor cannot span "
                            + "tenants, so each tenant's run starts from the beginning of its own bound",
                    afterId);
        }
        AtomicInteger published = new AtomicInteger();
        AtomicBoolean more = new AtomicBoolean(false);
        int tenants = tenantIterator.forEachActiveTenant(tenantId -> {
            BackfillResult tenantResult = pageThrough(null);
            published.addAndGet(tenantResult.published());
            if (tenantResult.more()) {
                more.set(true);
            }
        });
        BackfillResult aggregate = new BackfillResult(published.get(), null, more.get());
        log.info(
                "Workorder fact backfill run complete for a platform operator tenants={} published={} more={}",
                tenants,
                aggregate.published(),
                aggregate.more());
        return aggregate;
    }

    /**
     * Walk pages from {@code afterId} until the selection is exhausted or the per-run bound is
     * reached.
     *
     * <p>Keyset paging: each page resumes from the last id of the previous one, so a row that stops
     * matching mid-run (there is no such write path today, but none is required for the keyset
     * property to matter) cannot shift a surviving row out of the walk the way an offset page would.
     */
    private BackfillResult pageThrough(@Nullable UUID afterId) {
        int published = 0;
        UUID cursor = afterId;
        while (published < maxRowsPerRun) {
            int request = Math.min(pageSize, maxRowsPerRun - published);
            List<UUID> page = pagePublisher.publishPage(cursor, request);
            if (page.isEmpty()) {
                return new BackfillResult(published, cursor, false);
            }
            published += page.size();
            cursor = page.get(page.size() - 1);
            if (page.size() < request) {
                // A short page means the selection is exhausted; no further query is needed.
                return new BackfillResult(published, cursor, false);
            }
        }
        return new BackfillResult(published, cursor, true);
    }
}
