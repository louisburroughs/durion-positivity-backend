package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.WorkorderSearchResult;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Free-text workorder search matching the customer name or the workorder id.
 */
public interface WorkorderSearchService {

    /**
     * Search workorders by query string matching the customer name (resolved to
     * customer ids) or the workorder id directly. Resulting rows are enriched with
     * the resolved customer display name.
     *
     * @param q        free-text query (customer name or workorder id)
     * @param pageable pagination and sorting configuration
     * @return page of workorder search results enriched with {@code customerName}
     */
    @NonNull
    Page<WorkorderSearchResult> search(@NonNull String q, @NonNull Pageable pageable);
}
