package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.WorkorderStatusDetail;
import com.positivity.workorder.internal.dto.WorkorderStatusView;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public service API for Work-In-Progress (WIP) visibility.
 *
 * <p>
 * Callers must supply a {@code locationId} and indicate whether the request
 * is multi-location (i.e. the caller holds
 * {@code workorder:wip:view_all_locations}). Implementations decide how to
 * scope the query accordingly.
 */
public interface WipService {

    /**
     * Return a paginated list of workorders currently in an active WIP status.
     *
     * @param locationId    the primary location to filter by
     * @param multiLocation {@code true} when the caller is permitted to see
     *                      workorders across all locations
     * @param pageable      pagination and sort parameters
     * @return page of summary WIP views
     */
    Page<WorkorderStatusView> getWipWorkorders(
            @NonNull String locationId, boolean multiLocation, @NonNull Pageable pageable);

    /**
     * The cross-location WIP page restricted to a set of shops — the {@code multiLocation=true}
     * board for a caller whose {@code workorder:wip:view_all_locations} grant is location-scoped
     * (ADR-0061 §3, #1872). The controller expands the caller's reach to this set; the service
     * only queries it.
     *
     * @param shopIds the shops the caller may see; an empty set answers an empty page
     * @param pageable page request
     * @return paginated WIP workorders at those shops, enriched like {@link #getWipWorkorders}
     */
    Page<WorkorderStatusView> getWipWorkordersAtShops(@NonNull Set<UUID> shopIds, @NonNull Pageable pageable);

    /**
     * Return the full WIP detail for a single workorder.
     *
     * @param workorderId the UUID of the workorder to retrieve
     * @return full WIP detail
     */
    WorkorderStatusDetail getWipDetail(@NonNull UUID workorderId);
}
