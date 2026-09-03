package com.positivity.location.internal.service;

import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.entity.MobileUnitEntity;
import com.positivity.location.internal.repository.BayRepository;
import com.positivity.location.internal.repository.MobileUnitRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One transactional page of a fact backfill (issue #1668).
 *
 * <p>A separate bean from {@link FactBackfillServiceImpl} on purpose. The paging loop must call
 * each page through the Spring proxy for {@code @Transactional} to apply; a private or protected
 * method invoked from inside the same bean bypasses the proxy and would run with no transaction at
 * all. That is not a subtle degradation here — {@code OutboxEventWriter.publish} is
 * {@code Propagation.MANDATORY}, so an untransacted page throws rather than silently publishing
 * nothing.
 *
 * <p>{@code REQUIRES_NEW} makes each page independent: a backfill is a repair operation, and one
 * page failing should leave the pages already committed published rather than discarding the whole
 * run. Re-running is idempotent, so partial progress is strictly better than none.
 *
 * <p>Rows are published through the publisher's committed-state entry points, which skip the
 * per-row {@code flush()} the live mutation paths need: a backfill has no pending mutation to
 * flush, and flushing once per row over a persistence context that grows by an outbox entity per
 * row makes the page quadratic in its own size. This class flushes once, at the end of the page.
 *
 * <p>The page query takes a shared row lock — see {@code BayRepository.findBackfillPage} for why
 * that is required rather than defensive. Keeping each page short is therefore not only a memory
 * concern: it bounds how long a concurrent delete can be made to wait.
 */
@Component
@RequiredArgsConstructor
public class FactBackfillPagePublisher {

    /** Minimum UUID, so {@code id > MIN} matches every row on the first page of a run. */
    private static final UUID MIN_UUID = new UUID(0L, 0L);

    private final BayRepository bayRepository;
    private final MobileUnitRepository mobileUnitRepository;
    private final LocationFactPublisher locationFactPublisher;
    private final EntityManager entityManager;

    /** Publish one page of bay facts and return the rows published, in id order. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<BayEntity> publishBayPage(UUID afterId, int pageSize) {
        List<BayEntity> page = bayRepository.findBackfillPage(cursor(afterId), PageRequest.ofSize(pageSize));
        page.forEach(locationFactPublisher::bayChangedFromCommittedState);
        flushAndClear();
        return page;
    }

    /** Publish one page of mobile-unit facts and return the rows published, in id order. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<MobileUnitEntity> publishMobileUnitPage(UUID afterId, int pageSize) {
        List<MobileUnitEntity> page =
                mobileUnitRepository.findBackfillPage(cursor(afterId), PageRequest.ofSize(pageSize));
        page.forEach(locationFactPublisher::mobileUnitChangedFromCommittedState);
        flushAndClear();
        return page;
    }

    private static UUID cursor(UUID afterId) {
        return afterId == null ? MIN_UUID : afterId;
    }

    /**
     * Flush before clearing. The outbox rows this page queued are still pending inserts in the
     * persistence context; clearing without flushing first would detach them and the page would
     * commit having published nothing.
     */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
