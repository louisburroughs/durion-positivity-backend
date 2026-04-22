package com.positivity.vehicle.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.vehicle.internal.dto.SearchVehiclesRequest;
import com.positivity.vehicle.internal.dto.SearchVehiclesResponse;
import com.positivity.vehicle.service.VehicleSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
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

        private final VehicleSearchService searchService;

        @Operation(summary = "Search vehicles", description = "Search for vehicles by VIN, license plate, unit number, or description. "
                        + "Results are ranked by relevance: exact match > prefix match > contains match.")
        @ApiResponse(responseCode = "200", description = "Search results returned", content = @Content(schema = @Schema(implementation = SearchVehiclesResponse.class)))
        @ApiResponse(responseCode = "400", description = "Invalid search query")
        @PostMapping
        @EmitEvent(id = "VEHICLE_SEARCH", apiVersion = "1")
        public ResponseEntity<SearchVehiclesResponse> search(@RequestBody SearchVehiclesRequest request) {

                log.info(
                                "POST /v1/vehicles/search - query(mask)='{}', limit={}",
                                maskForLog(request != null ? request.getQuery() : null),
                                request != null ? request.getLimit() : null);

                var results = searchService.search(request);
                return ResponseEntity.ok(results);
        }

        @Operation(summary = "Search vehicles (query parameter)", description = "Alternative search endpoint using query parameters. Useful for browser-based queries.")
        @ApiResponse(responseCode = "200", description = "Search results returned", content = @Content(schema = @Schema(implementation = SearchVehiclesResponse.class)))
        @ApiResponse(responseCode = "400", description = "Invalid search query")
        @GetMapping
        @EmitEvent(id = "VEHICLE_SEARCH", apiVersion = "1")
        public ResponseEntity<SearchVehiclesResponse> searchByQuery(
                        @Parameter(description = "Search query (VIN, license plate, unit number, or description)") @RequestParam String q,
                        @Parameter(description = "Result limit (default 25, max 50)") @RequestParam(required = false, defaultValue = "25") Integer limit,
                        @Parameter(description = "Enable contains matching (default false)") @RequestParam(required = false, defaultValue = "false") Boolean enableContains) {

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
