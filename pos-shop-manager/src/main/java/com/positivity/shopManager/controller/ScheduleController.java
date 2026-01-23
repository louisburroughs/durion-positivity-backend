package com.positivity.shopManager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Schedule API", description = "Read-only schedule view operations for shop management UI")
@RestController
@RequestMapping("/v1/shop-manager")
@RequiredArgsConstructor
public class ScheduleController {

    @Operation(summary = "View schedules", description = "Read-only schedule view used by UI with optional filters.")
    @ApiResponse(responseCode = "200", description = "Schedules retrieved successfully.")
    @GetMapping("/{locationId}/schedules/view")
    public ResponseEntity<Object> viewSchedules(
            @Parameter(description = "Shop location ID", example = "1") @PathVariable Long locationId,
            @Parameter(description = "Optional filter parameters") @RequestParam(required = false) String filters) {
        log.info("Viewing schedules for location ID: {} with filters: {}", locationId, filters);
        // TODO: Implement schedule view logic with filtering
        return ResponseEntity.ok().build();
    }
}
