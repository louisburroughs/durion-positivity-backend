package com.positivity.vehicle.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.vehicle.internal.dto.SearchVehiclesRequest;
import com.positivity.vehicle.internal.dto.SearchVehiclesResponse;
import com.positivity.vehicle.internal.service.VehicleSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for vehicle search (CAP:091 Story #103).
 * Provides ranking-based vehicle discovery with multiple matching strategies.
 */
@Slf4j
@Tag(name = "Vehicle Search", description = "Search and discover vehicles with ranking-based results")
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/vehicles/search")
public class VehicleSearchController {

    private final VehicleSearchService searchService;

    @Operation(summary = "Search vehicles", description = "Search for vehicles by VIN, license plate, unit number, or description. "
            +
            "Results are ranked by relevance: exact match > prefix match > contains match.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Search results returned", content = @Content(schema = @Schema(implementation = SearchVehiclesResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid search query")
    })
    @PostMapping
    @EmitEvent(id = "VEHICLE_SEARCH", apiVersion = "1")
    public ResponseEntity<SearchVehiclesResponse> search(
            @RequestBody SearchVehiclesRequest request) {

        log.info("POST /v1/vehicles/search - query='{}', limit={}", request.getQuery(), request.getLimit());

        var results = searchService.search(request);
        return ResponseEntity.ok(results);
    }

    @Operation(summary = "Search vehicles (query parameter)", description = "Alternative search endpoint using query parameters. Useful for browser-based queries.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Search results returned", content = @Content(schema = @Schema(implementation = SearchVehiclesResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid search query")
    })
    @GetMapping
    @EmitEvent(id = "VEHICLE_SEARCH", apiVersion = "1")
    public ResponseEntity<SearchVehiclesResponse> searchByQuery(
            @Parameter(description = "Search query (VIN, license plate, unit number, or description)") @RequestParam String q,
            @Parameter(description = "Result limit (default 25, max 50)") @RequestParam(required = false, defaultValue = "25") Integer limit,
            @Parameter(description = "Enable contains matching (default false)") @RequestParam(required = false, defaultValue = "false") Boolean enableContains) {

        log.info("GET /v1/vehicles/search?q='{}' - limit={}", q, limit);

        var request = SearchVehiclesRequest.builder()
                .query(q)
                .limit(limit)
                .enableContainsMatching(enableContains)
                .build();

        var results = searchService.search(request);
        return ResponseEntity.ok(results);
    }
}
