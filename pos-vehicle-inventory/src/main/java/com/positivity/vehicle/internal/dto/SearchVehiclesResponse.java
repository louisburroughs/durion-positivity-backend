package com.positivity.vehicle.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Paginated vehicle search results")
public class SearchVehiclesResponse {

    @Schema(description = "Vehicle summaries matching the search query", requiredMode = REQUIRED)
    private List<VehicleSummary> results;

    @Schema(description = "Total number of vehicles matching the query", example = "42", requiredMode = REQUIRED)
    private Integer totalCount;

    @Schema(
            description = "Whether additional result pages are available beyond the current page",
            example = "true",
            requiredMode = REQUIRED)
    private Boolean hasMore;

    @Schema(
            description = "Echo of the originating search query",
            example = "1HGCM82633A004352",
            requiredMode = REQUIRED)
    private String query;
}
