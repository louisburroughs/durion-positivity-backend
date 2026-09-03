package com.positivity.location.internal.service;

import com.positivity.location.internal.config.FactBackfillService;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Regenerate-from-state backfill for bay and mobile-unit facts (issue #1668).
 *
 * <p>Pages through the owner's tables rather than loading them whole: a backfill runs against every
 * row in the module, and materializing all of them plus their outbox envelopes in one persistence
 * context is what turns a repair into an outage. Each page is a separate transaction owned by
 * {@link FactBackfillPagePublisher} — see that class for why the split is required rather than
 * stylistic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactBackfillServiceImpl implements FactBackfillService {

    private final FactBackfillPagePublisher pagePublisher;

    /** Rows per transaction. Bounds both heap and the size of a single outbox commit. */
    @Value("${pos.location.fact-backfill.page-size:500}")
    private int pageSize;

    @Override
    public int backfillBays() {
        int total = pageThrough(pagePublisher::publishBayPage);
        log.info("Bay fact backfill complete: {} facts queued", total);
        return total;
    }

    @Override
    public int backfillMobileUnits() {
        int total = pageThrough(pagePublisher::publishMobileUnitPage);
        log.info("Mobile unit fact backfill complete: {} facts queued", total);
        return total;
    }

    /**
     * Walk every page, publishing as it goes, and return the number of facts queued.
     *
     * <p>Sorted by id so paging is stable: an unsorted page over a table taking concurrent writes
     * can skip or repeat rows between pages, and a skipped row is a unit that stays invisible —
     * precisely the failure this backfill exists to fix.
     */
    private <T> int pageThrough(Function<Pageable, Page<T>> publishPage) {
        int total = 0;
        int pageNumber = 0;
        Page<T> page;
        do {
            page = publishPage.apply(PageRequest.of(pageNumber, pageSize, Sort.by("id")));
            total += page.getNumberOfElements();
            pageNumber++;
        } while (page.hasNext());
        return total;
    }
}
