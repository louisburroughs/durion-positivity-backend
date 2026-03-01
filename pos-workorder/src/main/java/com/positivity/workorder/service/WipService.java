package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.WorkorderStatusDetail;
import com.positivity.workorder.internal.dto.WorkorderStatusView;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

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
    Page<WorkorderStatusView> getWipWorkorders(@NonNull String locationId,
            boolean multiLocation,
            @NonNull Pageable pageable);

    /**
     * Return the full WIP detail for a single workorder.
     *
     * @param workorderId the UUID of the workorder to retrieve
     * @return full WIP detail
     */
    WorkorderStatusDetail getWipDetail(@NonNull UUID workorderId);
}
