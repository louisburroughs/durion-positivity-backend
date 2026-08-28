package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.EstimateSummaryResponse;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.EstimateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"workorder:estimate:view"})
@RequestMapping("/v1/workexec/estimates")
@Tag(name = "Estimate Search", description = "Estimate search and retrieval")
@RequiredArgsConstructor
public class EstimateSearchController {

    private final EstimateService estimateService;

    @Operation(operationId = "searchEstimates", summary = "Search Estimates With Filters", description = """
                    Searches estimates and returns a page of estimate summaries, either by free-text query or by \
                    exact customer and vehicle filters.
                    Use this tool when locating estimates by estimate number, customer name, estimate id, \
                    customer, or vehicle; use listEstimates instead for an unfiltered listing of every estimate.
                    Preconditions: none beyond the caller holding workorder:estimate:view; unmatched queries \
                    return an empty page rather than an error.
                    Required inputs: none are mandatory — a non-blank q takes precedence and causes customerId \
                    and vehicleId to be ignored; page size defaults to 25.
                    Emits a WORKORDER_ESTIMATE_SEARCH audit event; no estimate state changes — this is a \
                    read-only projection.
                    Returns 200 with an empty page when nothing matches; no 404 is produced for empty results.
                    """)
    @ApiResponse(responseCode = "200", description = "Page of estimate summaries returned.")
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_VIEW + "')")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SEARCH", apiVersion = "1")
    public Page<EstimateSummaryResponse> searchEstimates(
            @Parameter(
                            description =
                                    "Free-text query matching estimate number, customer name, or estimate id (optional)")
                    @RequestParam(required = false)
                    @Nullable
                    String q,
            @Parameter(description = "Filter by customer UUID (optional)") @RequestParam(required = false) @Nullable
                    UUID customerId,
            @Parameter(description = "Filter by vehicle UUID (optional)") @RequestParam(required = false) @Nullable
                    UUID vehicleId,
            @ParameterObject @PageableDefault(size = 25) Pageable pageable) {
        if (q != null && !q.isBlank()) {
            return estimateService.findEstimatesByQuery(q.trim(), pageable);
        }
        return estimateService.searchEstimates(customerId, vehicleId, pageable);
    }
}
