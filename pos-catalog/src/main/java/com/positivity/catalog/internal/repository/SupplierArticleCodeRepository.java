package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.SupplierArticleCodeEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierArticleCodeRepository
        extends JpaRepository<SupplierArticleCodeEntity, UUID>, JpaSpecificationExecutor<SupplierArticleCodeEntity> {

    Optional<SupplierArticleCodeEntity> findByVendorProfileIdAndProductId(UUID vendorProfileId, UUID productId);

    /**
     * The replay order: by id ascending, so a cursor resumes exactly where the previous page
     * stopped. Imposed by the search rather than taken from the caller, because it is what makes
     * {@code afterId} a cursor at all.
     */
    Sort BY_ID = Sort.by(Sort.Order.asc("id"));

    /**
     * A page of current-state rows for fact replay (CAP-320 #1347, following #1309's pattern),
     * ordered by id so a cursor can resume where the previous page stopped.
     *
     * <p>Cursor rather than offset paging, for the same reason as {@code ProductRepository}'s
     * equivalent: offsets shift under concurrent writes, and a row upserted mid-replay would
     * silently displace another out of the window.
     *
     * <p>The filter is a {@link FactReplaySearch} specification rather than a JPQL string of
     * {@code (:param IS NULL OR …)} clauses: see that class for why the string form returned 500
     * from PostgreSQL for every call while passing on H2 (issue #1891).
     *
     * @param afterId resume strictly after this row id, or null to start at the beginning
     * @param updatedSince only rows changed at or after this instant, or null for every row
     * @param pageable supplies the page size only — the replay is positioned by {@code afterId}, not
     *     by an offset, and the order is always {@link #BY_ID}
     * @return one page of supplier article codes, oldest id first
     */
    @NonNull
    default List<SupplierArticleCodeEntity> findForReplay(
            @Nullable UUID afterId, @Nullable Instant updatedSince, @NonNull Pageable pageable) {
        return findBy(FactReplaySearch.<SupplierArticleCodeEntity>matching(afterId, updatedSince), query -> {
            var sorted = query.sortBy(BY_ID);
            return (pageable.isUnpaged() ? sorted : sorted.limit(pageable.getPageSize())).all();
        });
    }
}
