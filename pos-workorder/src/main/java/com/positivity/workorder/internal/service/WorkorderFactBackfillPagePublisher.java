package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One transactional page of the actual-time fact backfill (#2021 AC8).
 *
 * <p>A separate bean from {@link WorkorderFactBackfillServiceImpl}, mirroring pos-location's
 * {@code FactBackfillPagePublisher} (issue #1668): the paging loop must call each page through the
 * Spring proxy for {@code @Transactional} to apply — a private or protected method invoked from
 * inside the same bean bypasses the proxy and would run with no transaction at all, and {@link
 * WorkorderFactPublisher#markChanged} needs an active transaction synchronization to register
 * against.
 *
 * <p>{@code REQUIRES_NEW} makes each page independent: a backfill is a repair operation, and one
 * page failing should leave the pages already committed published rather than discarding the whole
 * run. Re-running is idempotent (an equal-version fact is a no-op for a consumer), so partial
 * progress is strictly better than none.
 *
 * <p>This class only selects the page and marks it changed; the actual read-and-publish happens
 * inside {@link WorkorderFactPublisher#markChanged}'s own {@code beforeCommit} synchronization when
 * this method's transaction commits. There is no per-row flush here — unlike pos-location's page
 * publisher, this class never loads or mutates full entities itself, so there is nothing of its own
 * to flush.
 */
@Component
@RequiredArgsConstructor
public class WorkorderFactBackfillPagePublisher {

    /** Minimum UUID, so {@code id > MIN} matches every row on the first page of a run. */
    private static final UUID MIN_UUID = new UUID(0L, 0L);

    private final WorkorderRepository workorderRepository;
    private final WorkorderFactPublisher workorderFactPublisher;

    /** Publish one page of actual-time facts and return the workorder ids published, in id order. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> publishPage(@Nullable UUID afterId, int pageSize) {
        List<UUID> page = workorderRepository.findActualTimeBackfillPage(cursor(afterId), PageRequest.ofSize(pageSize));
        page.forEach(workorderFactPublisher::markChanged);
        return page;
    }

    private static UUID cursor(@Nullable UUID afterId) {
        return afterId == null ? MIN_UUID : afterId;
    }
}
