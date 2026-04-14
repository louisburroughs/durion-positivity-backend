package com.positivity.vehicle.internal.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Search results response (CAP:091 Story #103).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchVehiclesResponse {
    private List<VehicleSummary> results;
    private Integer totalCount;
    private Boolean hasMore;
    private String query;
}
