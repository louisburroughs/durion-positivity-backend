package com.positivity.workorder.internal.service;

import com.positivity.shared.dto.CountResponse;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate workorder counts (rung 1 of the answer resolution ladder). Computes counts server-side
 * via COUNT queries — never by loading and sizing a collection.
 */
public interface WorkorderCountService {

    /**
     * Count workorders grouped by status.
     *
     * @param statuses the statuses to include; when {@code null} or empty, every status is counted
     * @return the total and a per-status breakdown (statuses with zero matches are omitted)
     */
    @NonNull
    CountResponse countByStatuses(@Nullable Set<WorkorderStatus> statuses);

    /** Convenience for the common question "how many workorders are open?" (non-terminal statuses). */
    @NonNull
    default CountResponse countOpen() {
        return countByStatuses(WorkorderStatus.getOpenStatuses());
    }
}
