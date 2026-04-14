package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.DashboardResponse;
import java.time.LocalDate;
import org.jspecify.annotations.NonNull;

/**
 * Service interface for the Daily Dispatch Board Dashboard.
 * Aggregates workorder, mechanic, and bay data for a given date and location.
 */
public interface DashboardService {
    /**
     * Returns the dashboard aggregate for the given location and date.
     */
    DashboardResponse getDashboard(@NonNull String locationId, @NonNull LocalDate date);
}
