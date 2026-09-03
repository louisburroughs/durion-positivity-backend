package com.positivity.location.internal.service;

import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.entity.MobileUnitEntity;
import com.positivity.location.internal.repository.BayRepository;
import com.positivity.location.internal.repository.MobileUnitRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 */
@Component
@RequiredArgsConstructor
public class FactBackfillPagePublisher {

    private final BayRepository bayRepository;
    private final MobileUnitRepository mobileUnitRepository;
    private final LocationFactPublisher locationFactPublisher;
    private final EntityManager entityManager;

    /** Publish one page of bay facts and return the page, so the caller can ask for the next. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Page<BayEntity> publishBayPage(Pageable pageable) {
        Page<BayEntity> page = bayRepository.findAll(pageable);
        page.forEach(locationFactPublisher::bayChanged);
        flushAndClear();
        return page;
    }

    /** Publish one page of mobile-unit facts and return the page. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Page<MobileUnitEntity> publishMobileUnitPage(Pageable pageable) {
        Page<MobileUnitEntity> page = mobileUnitRepository.findAll(pageable);
        page.forEach(locationFactPublisher::mobileUnitChanged);
        flushAndClear();
        return page;
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
