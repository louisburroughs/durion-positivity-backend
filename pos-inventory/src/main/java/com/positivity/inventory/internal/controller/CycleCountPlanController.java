package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.service.CycleCountPlanService;
import com.positivity.security.common.SecurityContextHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/inventory/cycleCountPlans")
@RequiredArgsConstructor
@Tag(name = "Cycle Count Plans", description = "Cycle count plan management endpoints")
public class CycleCountPlanController {

        private final CycleCountPlanService cycleCountPlanService;

        @PostMapping
        @EmitEvent(id = "INVENTORY_CYCLE_COUNT_PLAN_CREATE", apiVersion = "1")
        @PreAuthorize("hasAuthority('inventory:cycle_count:initiate')")
        @Operation(summary = "Create cycle count plan", description = "Creates a cycle count plan and returns its configuration details.")
        @ApiResponse(responseCode = "201", description = "Cycle count plan created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CycleCountPlanResponse.class)))
        @ApiResponse(responseCode = "400", description = "Validation failure")
        @ApiResponse(responseCode = "403", description = "User lacks required permission")
        public ResponseEntity<CycleCountPlanResponse> createPlan(@RequestBody CreateCycleCountPlanRequest request) {
                String createdBy = SecurityContextHelper.getCurrentUsername()
                                .orElseThrow(() -> new IllegalStateException("No current user"));
                CycleCountPlanResponse response = cycleCountPlanService.createPlan(request, createdBy);
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        @GetMapping("/{planId}")
        @PreAuthorize("hasAuthority('inventory:cycle_count:view')")
        @Operation(summary = "Get cycle count plan", description = "Returns a cycle count plan by identifier.")
        @ApiResponse(responseCode = "200", description = "Cycle count plan returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CycleCountPlanResponse.class)))
        @ApiResponse(responseCode = "404", description = "Cycle count plan not found")
        public ResponseEntity<CycleCountPlanResponse> getPlan(
                        @Parameter(description = "Cycle count plan identifier", required = true) @PathVariable UUID planId) {
                return ResponseEntity.ok(cycleCountPlanService.getPlan(planId));
        }
}
