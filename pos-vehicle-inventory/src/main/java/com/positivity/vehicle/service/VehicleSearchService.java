package com.positivity.vehicle.service;

import com.positivity.vehicle.internal.dto.SearchVehiclesRequest;
import com.positivity.vehicle.internal.dto.SearchVehiclesResponse;

public interface VehicleSearchService {

    /**
     * Searches vehicles by query with ranking-based results.
     * Ranking tiers: exact match → prefix match → contains match (if enabled)
     */
    SearchVehiclesResponse search(SearchVehiclesRequest request);
}
