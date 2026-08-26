package com.positivity.vehicle.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.vehicle.internal.dto.SearchVehiclesRequest;
import com.positivity.vehicle.internal.dto.SearchVehiclesResponse;
import com.positivity.vehicle.internal.security.VehicleInventoryPermissions;
import com.positivity.vehicle.service.VehicleSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for vehicle search (CAP:091 Story #103).
 * Provides ranking-based vehicle discovery with multiple matching strategies.
 */
@Slf4j
@Tag(name = "Vehicle Search", description = "Search and discover vehicles with ranking-based results")
@RequiredArgsConstructor
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@PreAuthorize("isAuthenticated()")
@RequestMapping("/v1/vehicles/search")
public class VehicleSearchController {

    private static final String SEARCH_EXAMPLE = """
            {"query":"1HGCM8",
             "limit":25,
             "enableContainsMatching":false}
            """;

    private final VehicleSearchService searchService;

    @Operation(operationId = "searchVehicles", summary = "Search vehicles", description = """
                    Searches vehicle registry records with a single free-text query, ranking exact matches ahead \
                    of prefix matches ahead of optional contains matches.
                    Use this tool for vehicle discovery from a JSON body; use searchVehiclesByQuery for the \
                    query-parameter variant, and use getVehicleByVin instead when the full 17-character VIN is \
                    already known.
                    Preconditions: none beyond registry data existing; the match field is inferred from the query \
                    shape, with six or more alphanumeric characters searched as a VIN (seventeen as an exact VIN), \
                    shorter queries searched against license plates, and the remainder searched against unit \
                    number plus description.
                    Required inputs: query (non-blank, at least 3 characters, or 6 when VIN-shaped); limit \
                    defaults to 25 and is capped at 50, enableContainsMatching defaults to false, and cursor is \
                    reserved and currently ignored.
                    Emits a VEHICLE_SEARCH event; no vehicle state changes.
                    Returns 200 with ranked results plus totalCount and hasMore, and 400 with a VALIDATION_ERROR \
                    ApiError when the query is empty or shorter than the minimum for its inferred type.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Search results returned",
            content = @Content(schema = @Schema(implementation = SearchVehiclesResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid search query")
    @PreAuthorize("hasAuthority('" + VehicleInventoryPermissions.SEARCH_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {VehicleInventoryPermissions.SEARCH_VIEW})
    @PostMapping
    @EmitEvent(id = "VEHICLE_SEARCH", apiVersion = "1")
    public ResponseEntity<SearchVehiclesResponse> search(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Free-text search criteria with an optional result limit and matching"
                                    + " strategy flag.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "VIN prefix search", value = SEARCH_EXAMPLE)))
                    @RequestBody
                    SearchVehiclesRequest request) {

        log.info(
                "POST /v1/vehicles/search - query(mask)='{}', limit={}",
                maskForLog(request != null ? request.getQuery() : null),
                request != null ? request.getLimit() : null);

        var results = searchService.search(request);
        return ResponseEntity.ok(results);
    }

    @Operation(operationId = "searchVehiclesByQuery", summary = "Search vehicles (query parameter)", description = """
                    Searches vehicle registry records using query parameters, applying the same ranking as \
                    searchVehicles with exact matches first, then prefix matches, then optional contains matches.
                    Use this tool for browser-friendly or link-style searches; use searchVehicles instead when the \
                    client can send a JSON body, and use getVehicleByVin when the full 17-character VIN is already \
                    known.
                    Preconditions: none beyond registry data existing; the match field is inferred from the query \
                    shape exactly as in searchVehicles.
                    Required inputs: q (non-blank, at least 3 characters, or 6 when VIN-shaped); limit defaults to \
                    25 and is capped at 50, and enableContains defaults to false.
                    Emits a VEHICLE_SEARCH event; no vehicle state changes.
                    Returns 200 with ranked results plus totalCount and hasMore, and 400 with a VALIDATION_ERROR \
                    ApiError when q is empty or shorter than the minimum for its inferred type.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Search results returned",
            content = @Content(schema = @Schema(implementation = SearchVehiclesResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid search query")
    @PreAuthorize("hasAuthority('" + VehicleInventoryPermissions.SEARCH_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {VehicleInventoryPermissions.SEARCH_VIEW})
    @GetMapping
    @EmitEvent(id = "VEHICLE_SEARCH", apiVersion = "1")
    public ResponseEntity<SearchVehiclesResponse> searchByQuery(
            @Parameter(description = "Search query (VIN, license plate, unit number, or description)") @RequestParam
                    String q,
            @Parameter(description = "Result limit (default 25, max 50)")
                    @RequestParam(required = false, defaultValue = "25")
                    Integer limit,
            @Parameter(description = "Enable contains matching (default false)")
                    @RequestParam(required = false, defaultValue = "false")
                    Boolean enableContains) {

        log.info("GET /v1/vehicles/search?q(mask)='{}' - limit={}", maskForLog(q), limit);

        var request = SearchVehiclesRequest.builder()
                .query(q)
                .limit(limit)
                .enableContainsMatching(enableContains)
                .build();

        var results = searchService.search(request);
        return ResponseEntity.ok(results);
    }

    private String maskForLog(String value) {
        if (value == null) {
            return "null";
        }
        String sanitized = value.replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
