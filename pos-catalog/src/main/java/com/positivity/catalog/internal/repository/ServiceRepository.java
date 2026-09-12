package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ServiceEntity;
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

public interface ServiceRepository extends JpaRepository<ServiceEntity, UUID>, JpaSpecificationExecutor<ServiceEntity> {
    List<ServiceEntity> findByName(String name);

    Optional<ServiceEntity> findByOperationCode(String operationCode);

    List<ServiceEntity> findByNameContainingIgnoreCaseOrderByNameAsc(String name);

    /**
     * The replay order: by id ascending, so a cursor resumes exactly where the previous page
     * stopped. Imposed by the search rather than taken from the caller, because it is what makes
     * {@code afterId} a cursor at all.
     */
    Sort BY_ID = Sort.by(Sort.Order.asc("id"));

    /**
     * A page of services for fact replay (#1306), ordered by id so a cursor can resume where the
     * previous page stopped.
     *
     * <p>Cursor rather than offset paging, for the reason the product replay uses one: offsets
     * shift under concurrent writes, and a service created mid-replay would displace another out of
     * the window, leaving a replica short of exactly the fact the replay existed to deliver.
     *
     * <p>The filter is a {@link FactReplaySearch} specification rather than a JPQL string of
     * {@code (:param IS NULL OR …)} clauses: see that class for why the string form returned 500
     * from PostgreSQL for every call while passing on H2 (issue #1891).
     *
     * @param afterId resume strictly after this service id, or null to start at the beginning
     * @param updatedSince only services changed at or after this instant, or null for every service
     * @param pageable supplies the page size only — the replay is positioned by {@code afterId}, not
     *     by an offset, and the order is always {@link #BY_ID}
     * @return one page of services, oldest id first
     */
    @NonNull
    default List<ServiceEntity> findForReplay(
            @Nullable UUID afterId, @Nullable Instant updatedSince, @NonNull Pageable pageable) {
        return findBy(FactReplaySearch.<ServiceEntity>matching(afterId, updatedSince), query -> {
            var sorted = query.sortBy(BY_ID);
            return (pageable.isUnpaged() ? sorted : sorted.limit(pageable.getPageSize())).all();
        });
    }
}
