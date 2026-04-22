package com.positivity.shopmanager.internal.controller;

import com.positivity.shopmanager.service.WorkorderOperationalContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Workorder Operational Context API", description = "Read-only operational context view for workorders")
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/shop-manager")
@RequiredArgsConstructor
public class WorkorderOperationalContextController {
        private final WorkorderOperationalContextService workorderOperationalContextService;

        @Operation(summary = "View workorder operational context", description = "Read-only operational context view for workorders used by UI with optional filters.")
        @ApiResponse(responseCode = "200", description = "Operational context retrieved successfully.")
        @GetMapping("/{locationId}/workorders/{workorderId}/operationalContext")
        @PreAuthorize("hasAuthority('shop:schedule:view')")
        public ResponseEntity<Object> getOperationalContext(
                        @Parameter(description = "Shop location ID", example = "1") @PathVariable Long locationId,
                        @Parameter(description = "Workorder ID", example = "1") @PathVariable Long workorderId,
                        @Parameter(description = "Optional filter parameters") @RequestParam(required = false) String filters) {
                log.info(
                                "Viewing operational context for location ID: {}, workorder ID: {} with filters: {}",
                                locationId,
                                workorderId,
                                filters);
                return ResponseEntity.ok(
                                workorderOperationalContextService.getOperationalContext(locationId, workorderId,
                                                filters));
        }
}
