package com.positivity.supplier.internal.repository;

import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Applies a search's own ordering to the caller's page request.
 *
 * <p>The three {@code Specification}-backed searches in this package impose their result order
 * themselves rather than taking it from the caller, because the order is part of each endpoint's
 * contract. Each therefore has to rebuild the incoming {@link Pageable} around its own {@link Sort},
 * and each has to cope with {@code Pageable.unpaged()}: it reports a page size of zero, which {@link
 * PageRequest#of} rejects, so an unpaged request must bypass it rather than throw before the search
 * runs. That was three identical lines in three interfaces; it is one here.
 */
final class SearchPaging {

    private SearchPaging() {}

    /**
     * The caller's page, re-sorted by the search's own order.
     *
     * @param pageable the caller's request, paged or unpaged
     * @param order the ordering the search imposes
     * @return an equivalent request carrying {@code order}
     */
    @NonNull
    static Pageable sortedBy(@NonNull Pageable pageable, @NonNull Sort order) {
        return pageable.isUnpaged()
                ? Pageable.unpaged(order)
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), order);
    }
}
