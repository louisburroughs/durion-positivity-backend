package com.positivity.location.internal.service;

import com.positivity.location.internal.config.FactBackfillService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Regenerate-from-state backfill for bay and mobile-unit facts (issue #1668).
 *
 * <p>Pages through the owner's tables rather than loading them whole: a backfill runs against every
 * row in the module, and materializing all of them plus their outbox envelopes in one persistence
 * context is what turns a repair into an outage. Each page is a separate transaction owned by
 * {@link FactBackfillPagePublisher} — see that class for why the split is required rather than
 * stylistic.
 *
 * <p>A run stops at {@code maxRowsPerRun} and reports the cursor to resume from, so a large estate
 * is walked over several commands instead of one unbounded pass on the Kafka listener thread. See
 * {@link FactBackfillService} for why that bound exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactBackfillServiceImpl implements FactBackfillService {

    private final FactBackfillPagePublisher pagePublisher;

    /** Rows per transaction. Bounds heap, outbox commit size, and how long a page holds its lock. */
    @Value("${pos.location.fact-backfill.page-size:500}")
    private int pageSize;

    /** Rows per command, after which the run reports a resume cursor and stops. */
    @Value("${pos.location.fact-backfill.max-rows-per-run:20000}")
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
            throw new IllegalStateException("pos.location.fact-backfill.page-size must be >= 1, was " + pageSize);
        }
        if (maxRowsPerRun < 1) {
            throw new IllegalStateException(
                    "pos.location.fact-backfill.max-rows-per-run must be >= 1, was " + maxRowsPerRun);
        }
    }

    @Override
    public BackfillResult backfillBays(@Nullable UUID afterId) {
        BackfillResult result = pageThrough(afterId, pagePublisher::publishBayPage, bay -> bay.getId());
        log.info(
                "Bay fact backfill run complete: {} facts queued, lastId={}, more={}",
                result.published(),
                result.lastId(),
                result.more());
        return result;
    }

    @Override
    public BackfillResult backfillMobileUnits(@Nullable UUID afterId) {
        BackfillResult result = pageThrough(afterId, pagePublisher::publishMobileUnitPage, unit -> unit.getId());
        log.info(
                "Mobile unit fact backfill run complete: {} facts queued, lastId={}, more={}",
                result.published(),
                result.lastId(),
                result.more());
        return result;
    }

    /**
     * Walk pages from {@code afterId} until the table is exhausted or the per-run bound is reached.
     *
     * <p>Keyset paging: each page resumes from the last id of the previous one, so a row deleted
     * mid-run cannot shift a surviving row out of the walk the way an offset page would.
     */
    private <T> BackfillResult pageThrough(
            @Nullable UUID afterId, BiFunction<UUID, Integer, List<T>> publishPage, Function<T, UUID> idOf) {
        int published = 0;
        UUID cursor = afterId;
        while (published < maxRowsPerRun) {
            int request = Math.min(pageSize, maxRowsPerRun - published);
            List<T> page = publishPage.apply(cursor, request);
            if (page.isEmpty()) {
                return new BackfillResult(published, cursor, false);
            }
            published += page.size();
            cursor = idOf.apply(page.get(page.size() - 1));
            if (page.size() < request) {
                // A short page means the table is exhausted; no further query is needed.
                return new BackfillResult(published, cursor, false);
            }
        }
        return new BackfillResult(published, cursor, true);
    }
}
