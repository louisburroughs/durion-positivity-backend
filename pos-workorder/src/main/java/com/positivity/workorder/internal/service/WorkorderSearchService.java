package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.WorkorderNumberRef;
import com.positivity.workorder.internal.dto.WorkorderSearchResult;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Free-text workorder search matching the customer name or the workorder id, with optional
 * structured filters (E12, #1600).
 */
public interface WorkorderSearchService {

    /**
     * Search workorders by query string matching the customer name (resolved to
     * customer ids) or the workorder id directly, optionally restricted to an exact
     * customer and/or vehicle, an exact status, a creation-date window, and/or a technician.
     * Resulting rows are enriched with the resolved customer display name.
     *
     * @param q            free-text query (customer name or workorder id)
     * @param customerId   exact customer filter combinable with {@code q}, or {@code null}
     * @param vehicleId    exact vehicle filter combinable with {@code q}, or {@code null}
     * @param statuses     status values to restrict to; {@code null} or empty means no restriction
     *                     (every status matches). Each element must be a real {@link WorkorderStatus}
     *                     — validated at the controller boundary (Spring's enum bind failure on an
     *                     unrecognized value) before this is called. Several values may be supplied
     *                     in one call — repeated {@code status} query params or one comma-separated
     *                     value — so an "open work orders" query is one call, not one call per open
     *                     status (#1676; previously mirrored {@code InvoiceSearchService}'s
     *                     single-status filter, #1599/E11, and required a loop).
     * @param createdFrom  inclusive lower bound on the workorder's creation date, or {@code null}
     *                     for no lower bound
     * @param createdTo    inclusive upper bound on the workorder's creation date, or {@code null}
     *                     for no upper bound
     * @param technicianId restrict to workorders with at least one labor entry
     *                     ({@code WorkorderLaborEntry.technicianId}) recorded for this technician
     *                     — the same attribution basis as {@code getTechnicianLaborAnalytics}'s
     *                     {@code billedHours} signal, not the workorder's currently assigned
     *                     technician. {@code null} means no restriction.
     * @param pageable     pagination and sorting configuration
     * @return page of workorder search results enriched with {@code customerName}
     */
    @NonNull
    Page<WorkorderSearchResult> search(
            @NonNull String q,
            @Nullable UUID customerId,
            @Nullable UUID vehicleId,
            @Nullable Collection<WorkorderStatus> statuses,
            @Nullable LocalDate createdFrom,
            @Nullable LocalDate createdTo,
            @Nullable UUID technicianId,
            @NonNull Pageable pageable);

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
