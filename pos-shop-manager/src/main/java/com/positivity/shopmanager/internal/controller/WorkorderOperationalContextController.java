package com.positivity.shopmanager.internal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.positivity.shopmanager.service.SourceEligibilityService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Workorder Operational Context API", description = "Read-only operational context view for workorders")
@RestController
@RequestMapping("/v1/shop-manager")
@RequiredArgsConstructor
public class WorkorderOperationalContextController {
    private static final String LOCATION_ID = "locationId";
    private static final String WORKORDER_ID = "workorderId";
    private final SourceEligibilityService sourceEligibilityService;

    @Operation(summary = "View workorder operational context", description = "Read-only operational context view for workorders used by UI with optional filters.")
    @ApiResponse(responseCode = "200", description = "Operational context retrieved successfully.")
    @GetMapping("/{locationId}/workorders/{workorderId}/operationalContext")
    @PreAuthorize("hasAuthority('shop:schedule:view')")
    public ResponseEntity<Object> viewOpenWorkordersByShop(
            @Parameter(description = "Shop location ID", example = "1") @PathVariable Long locationId,
            @Parameter(description = "Workorder ID", example = "1") @PathVariable Long workorderId,
            @Parameter(description = "Optional filter parameters") @RequestParam(required = false) String filters) {
        log.info("Viewing operational context for location ID: {}, workorder ID: {} with filters: {}",
                locationId, workorderId, filters);

        Map<String, String> parsedFilters = parseFilters(filters);
        String facilityId = String.valueOf(locationId);
        String workOrderRef = String.valueOf(workorderId);
        String workorderStatus = sourceEligibilityService.getWorkOrderStatus(workOrderRef, facilityId);
        String appointmentId = sourceEligibilityService.getExistingAppointmentId("WORKORDER", workOrderRef, facilityId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put(LOCATION_ID, locationId);
        response.put(WORKORDER_ID, workorderId);
        response.put("filtersApplied", parsedFilters);

        if (!matchesFilters(parsedFilters, locationId, workorderId, workorderStatus, appointmentId)) {
            response.put("results", Collections.emptyList());
            return ResponseEntity.ok(response);
        }

        Map<String, Object> operationalContext = new LinkedHashMap<>();
        operationalContext.put(LOCATION_ID, locationId);
        operationalContext.put(WORKORDER_ID, workorderId);
        operationalContext.put("workorderStatus", workorderStatus);
        operationalContext.put("appointmentId", appointmentId);
        operationalContext.put("appointmentLinked", appointmentId != null);

        response.put("results", List.of(operationalContext));
        return ResponseEntity.ok(response);
    }

    private Map<String, String> parseFilters(String filters) {
        if (filters == null || filters.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        Arrays.stream(filters.split("[,;&]"))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .forEach(token -> {
                    String[] split = token.split("[:=]", 2);
                    if (split.length == 2 && !split[0].isBlank()) {
                        parsed.put(split[0].trim(), split[1].trim());
                    }
                });
        return parsed;
    }

    private boolean matchesFilters(
            Map<String, String> filters,
            Long locationId,
            Long workorderId,
            String workorderStatus,
            String appointmentId) {
        if (filters.isEmpty()) {
            return true;
        }

        String locationFilter = filters.get(LOCATION_ID);
        if (locationFilter != null && !locationFilter.equals(String.valueOf(locationId))) {
            return false;
        }

        String workorderFilter = filters.get(WORKORDER_ID);
        if (workorderFilter != null && !workorderFilter.equals(String.valueOf(workorderId))) {
            return false;
        }

        String statusFilter = filters.get("status");
        if (statusFilter != null && (workorderStatus == null || !statusFilter.equalsIgnoreCase(workorderStatus))) {
            return false;
        }

        String appointmentLinkedFilter = filters.get("appointmentLinked");
        if (appointmentLinkedFilter != null) {
            boolean expected = Boolean.parseBoolean(appointmentLinkedFilter);
            if (expected != (appointmentId != null)) {
                return false;
            }
        }

        String appointmentFilter = filters.get("appointmentId");
        return appointmentFilter == null || appointmentFilter.equals(appointmentId);
    }
}
