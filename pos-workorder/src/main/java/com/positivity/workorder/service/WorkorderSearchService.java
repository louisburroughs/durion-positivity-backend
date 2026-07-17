package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.WorkorderNumberRef;
import com.positivity.workorder.internal.dto.WorkorderSearchResult;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Free-text workorder search matching the customer name or the workorder id.
 */
public interface WorkorderSearchService {

    /**
     * Search workorders by query string matching the customer name (resolved to
     * customer ids) or the workorder id directly, optionally restricted to an exact
     * customer and/or vehicle. Resulting rows are enriched with the resolved
     * customer display name.
     *
     * @param q          free-text query (customer name or workorder id)
     * @param customerId exact customer filter combinable with {@code q}, or {@code null}
     * @param vehicleId  exact vehicle filter combinable with {@code q}, or {@code null}
     * @param pageable   pagination and sorting configuration
     * @return page of workorder search results enriched with {@code customerName}
     */
    @NonNull
    Page<WorkorderSearchResult> search(
            @NonNull String q, @Nullable UUID customerId, @Nullable UUID vehicleId, @NonNull Pageable pageable);

    /**
     * Resolve a batch of workorder ids to their human workorder numbers. Unknown ids
     * are omitted from the result. Used by sibling services that store only the
     * workorder id and need the human number for finder/search enrichment.
     *
     * @param workorderIds workorder ids to resolve
     * @return id-to-number pairings for the ids that exist
     */
    @NonNull
    List<WorkorderNumberRef> resolveNumbers(@NonNull Collection<UUID> workorderIds);
}
