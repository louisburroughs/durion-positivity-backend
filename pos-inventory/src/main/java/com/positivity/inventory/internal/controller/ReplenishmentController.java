package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.service.ReplenishmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/replenishment")
@RequiredArgsConstructor
@Tag(name = "Replenishment", description = "Replenishment task and policy endpoints")
public class ReplenishmentController {

        private final ReplenishmentService replenishmentService;

        @GetMapping("/tasks")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "inventory:on_hand:view" })
        @PreAuthorize("hasAuthority('inventory:on_hand:view')")
        @Operation(operationId = "listReplenishmentTasks", summary = "List replenishment tasks", description = "Returns replenishment tasks that should be fulfilled.", tags = {
                        "Replenishment" })
        @ApiResponse(responseCode = "200", description = "Replenishment tasks returned", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ReplenishmentTaskResponse.class))))
        public ResponseEntity<List<ReplenishmentTaskResponse>> getReplenishmentTasks() {
                return ResponseEntity.ok(replenishmentService.getReplenishmentTasks());
        }

        @GetMapping("/policies")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "inventory:on_hand:view" })
        @PreAuthorize("hasAuthority('inventory:on_hand:view')")
        @Operation(operationId = "listReplenishmentPolicies", summary = "List replenishment policies", description = "Returns configured replenishment policies.", tags = {
                        "Replenishment" })
        @ApiResponse(responseCode = "200", description = "Replenishment policies returned (paged)", content = @Content(mediaType = "application/json", schema = @Schema(description = "Page of replenishment policies")))
        public ResponseEntity<Page<ReplenishmentPolicyResponse>> getReplenishmentPolicies(
                        @io.swagger.v3.oas.annotations.Parameter(description = "Location identifier") @RequestParam(required = false) UUID locationId,
                        @PageableDefault(size = 20) Pageable pageable) {
                return ResponseEntity.ok(replenishmentService.getReplenishmentPolicies(locationId, pageable));
        }

        @PostMapping("/policies")
        @EmitEvent(id = "INVENTORY_REPLENISHMENT_POLICY_CREATE", apiVersion = "1")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "inventory:adjustment:create" })
        @PreAuthorize("hasAuthority('inventory:adjustment:create')")
        @Operation(operationId = "createReplenishmentPolicy", summary = "Create replenishment policy", description = "Creates a replenishment policy used to generate replenishment tasks.", tags = {
                        "Replenishment" })
        @ApiResponse(responseCode = "201", description = "Replenishment policy created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReplenishmentPolicyResponse.class)))
        @ApiResponse(responseCode = "400", description = "Validation failure")
        public ResponseEntity<ReplenishmentPolicyResponse> createReplenishmentPolicy(
                        @Valid @RequestBody CreateReplenishmentPolicyRequest request) {
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(replenishmentService.createReplenishmentPolicy(request));
        }
}
