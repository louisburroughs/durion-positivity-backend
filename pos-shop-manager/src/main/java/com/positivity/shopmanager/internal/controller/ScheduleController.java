package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.internal.dto.ScheduleViewRequest;
import com.positivity.shopmanager.internal.dto.ScheduleViewResponse;
import com.positivity.shopmanager.service.AppointmentsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Schedule API", description = "Read-only schedule view operations for shop management UI")
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = { "shop:schedule:view" })
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ScheduleController {

        private final AppointmentsService appointmentsService;

        @Operation(summary = "View schedule", description = "Read-only schedule view for a location and date.")
        @ApiResponse(responseCode = "200", description = "Schedule retrieved successfully.")
        @ApiResponse(responseCode = "400", description = "Invalid input parameters")
        @ApiResponse(responseCode = "403", description = "Not authorized for this location")
        @ApiResponse(responseCode = "404", description = "Location or resource not found")
        @GetMapping("/schedules/view")
        @EmitEvent(id = "SHOPMGR_SCHEDULE_VIEW", apiVersion = "1")
        @PreAuthorize("hasAuthority('shop:schedule:view')")
        public ResponseEntity<ScheduleViewResponse> viewSchedule(
                        @Parameter(description = "Location ID", required = true) @RequestParam UUID locationId,
                        @Parameter(description = "Date in YYYY-MM-DD", required = true) @RequestParam LocalDate date,
                        @Parameter(description = "Optional resource type filter") @RequestParam(required = false) String resourceType,
                        @Parameter(description = "Optional single resource filter") @RequestParam(required = false) String resourceId,
                        @Parameter(description = "Include HR availability overlay", required = false) @RequestParam(defaultValue = "false") boolean includeAvailabilityOverlay,
                        @Parameter(description = "Schedule window range", required = false) @RequestParam(defaultValue = "LOCATION_HOURS") String range,
                        @Parameter(description = "Correlation ID for request tracing") @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
                ScheduleViewRequest request = new ScheduleViewRequest();
                request.setLocationId(locationId);
                request.setDate(date);
                request.setResourceType(resourceType);
                request.setResourceId(resourceId);
                request.setIncludeAvailabilityOverlay(includeAvailabilityOverlay);
                request.setRange(range);
                log.info(
                                "Schedule view requested. locationId={}, date={}, resourceType={}, resourceId={}, includeAvailabilityOverlay={}, range={}, X-Correlation-Id={}",
                                locationId,
                                date,
                                resourceType,
                                resourceId,
                                includeAvailabilityOverlay,
                                range,
                                correlationId);
                ScheduleViewResponse response = appointmentsService.getScheduleView(request, correlationId);
                return ResponseEntity.ok(response);
        }
}
