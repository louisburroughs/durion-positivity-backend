package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.client.PeopleAvailabilityClient;
import com.positivity.workorder.internal.client.ShopmgrOperationalContextClient;
import com.positivity.workorder.internal.dto.DashboardResponse;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.service.DashboardService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link DashboardService} for the Daily Dispatch Board Dashboard.
 * Aggregates workorder, mechanic, and bay data for conflict detection and display.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final WorkorderRepository workorderRepository;
    private final PeopleAvailabilityClient peopleAvailabilityClient;
    private final ShopmgrOperationalContextClient shopmgrOperationalContextClient;

    @Override
    public DashboardResponse getDashboard(@NonNull String locationId, @NonNull LocalDate date) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
