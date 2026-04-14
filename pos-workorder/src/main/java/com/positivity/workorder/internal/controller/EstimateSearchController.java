package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.EstimateSummaryResponse;
import com.positivity.workorder.service.EstimateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/workexec/estimates")
@Tag(name = "Estimate Search", description = "Estimate search and retrieval")
@RequiredArgsConstructor
public class EstimateSearchController {

    private final EstimateService estimateService;

    @Operation(
            summary = "Search estimates",
            description = "Paginated search for estimates filtered by optional customerId and/or vehicleId.")
    @ApiResponse(responseCode = "200", description = "Page of estimate summaries returned.")
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('workorder:estimate:view')")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SEARCH", apiVersion = "1")
    public Page<EstimateSummaryResponse> searchEstimates(
            @Parameter(description = "Filter by customer UUID (optional)") @RequestParam(required = false) @Nullable
                    UUID customerId,
            @Parameter(description = "Filter by vehicle UUID (optional)") @RequestParam(required = false) @Nullable
                    UUID vehicleId,
            @Parameter(schema = @Schema(implementation = Pageable.class)) @PageableDefault(size = 25)
                    Pageable pageable) {
        return estimateService.searchEstimates(customerId, vehicleId, pageable);
    }
}
