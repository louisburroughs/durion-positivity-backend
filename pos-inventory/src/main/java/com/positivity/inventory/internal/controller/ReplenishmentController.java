package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.internal.dto.replenishment.SnoozeReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.UpdateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @PreAuthorize("hasAuthority('inventory:on_hand:view')")
    @Operation(
            operationId = "listReplenishmentTasks",
            summary = "List replenishment tasks",
            description = "Returns replenishment tasks that should be fulfilled.",
            tags = {"Replenishment"})
    @ApiResponse(
            responseCode = "200",
            description = "Replenishment tasks returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ReplenishmentTaskResponse.class))))
    public ResponseEntity<List<ReplenishmentTaskResponse>> getReplenishmentTasks() {
        return ResponseEntity.ok(replenishmentService.getReplenishmentTasks());
    }

    @GetMapping("/policies")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @PreAuthorize("hasAuthority('inventory:on_hand:view')")
    @Operation(
            operationId = "listReplenishmentPolicies",
            summary = "List replenishment policies",
            description = "Returns configured replenishment policies.",
            tags = {"Replenishment"})
    @ApiResponse(
            responseCode = "200",
            description = "Replenishment policies returned (paged)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(description = "Page of replenishment policies")))
    public ResponseEntity<Page<ReplenishmentPolicyResponse>> getReplenishmentPolicies(
            @io.swagger.v3.oas.annotations.Parameter(description = "Location identifier")
                    @RequestParam(required = false)
                    UUID locationId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(replenishmentService.getReplenishmentPolicies(locationId, pageable));
    }

    @PostMapping("/policies")
    @EmitEvent(id = "INVENTORY_REPLENISHMENT_POLICY_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:replenishment:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.REPLENISHMENT_MANAGE + "')")
    @Operation(
            operationId = "createReplenishmentPolicy",
            summary = "Create replenishment policy",
            description = "Creates a replenishment policy used to generate replenishment tasks.",
            tags = {"Replenishment"})
    @ApiResponse(
            responseCode = "201",
            description = "Replenishment policy created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReplenishmentPolicyResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure")
    public ResponseEntity<ReplenishmentPolicyResponse> createReplenishmentPolicy(
            @Valid @RequestBody CreateReplenishmentPolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(replenishmentService.createReplenishmentPolicy(request));
    }

    @PutMapping("/policies/{policyId}")
    @EmitEvent(id = "INVENTORY_REPLENISHMENT_POLICY_UPDATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:replenishment:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.REPLENISHMENT_MANAGE + "')")
    @Operation(
            operationId = "updateReplenishmentPolicy",
            summary = "Update replenishment policy",
            description = "Full-replace update of a policy's min/max thresholds and tuning fields (order multiple,"
                    + " lead-time override, preferred source type, active flag). Omitted optional fields are"
                    + " cleared or reset to defaults. Location, SKU, and snooze state are not updatable here;"
                    + " snooze is managed by the snooze endpoint.",
            tags = {"Replenishment"})
    @ApiResponse(
            responseCode = "200",
            description = "Replenishment policy updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReplenishmentPolicyResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure")
    @ApiResponse(responseCode = "404", description = "Policy not found")
    public ResponseEntity<ReplenishmentPolicyResponse> updateReplenishmentPolicy(
            @io.swagger.v3.oas.annotations.Parameter(description = "Replenishment policy identifier") @PathVariable
                    UUID policyId,
            @Valid @RequestBody UpdateReplenishmentPolicyRequest request) {
        return ResponseEntity.ok(replenishmentService.updateReplenishmentPolicy(policyId, request));
    }

    @PostMapping("/policies/{policyId}/snooze")
    @EmitEvent(id = "INVENTORY_REPLENISHMENT_POLICY_SNOOZE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:replenishment:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.REPLENISHMENT_MANAGE + "')")
    @Operation(
            operationId = "snoozeReplenishmentPolicy",
            summary = "Snooze or unsnooze a replenishment policy",
            description = "Excludes the policy from scan, event, and needs evaluation until the given future"
                    + " instant (Odoo orderpoint snooze). A null snoozedUntil clears an existing snooze —"
                    + " there is no separate unsnooze endpoint. A non-null instant that is not in the future"
                    + " is rejected with 422.",
            tags = {"Replenishment"})
    @ApiResponse(
            responseCode = "200",
            description = "Snooze state updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReplenishmentPolicyResponse.class)))
    @ApiResponse(responseCode = "404", description = "Policy not found")
    @ApiResponse(responseCode = "422", description = "snoozedUntil is not in the future")
    public ResponseEntity<ReplenishmentPolicyResponse> snoozeReplenishmentPolicy(
            @io.swagger.v3.oas.annotations.Parameter(description = "Replenishment policy identifier") @PathVariable
                    UUID policyId,
            @Valid @RequestBody SnoozeReplenishmentPolicyRequest request) {
        return ResponseEntity.ok(replenishmentService.snoozeReplenishmentPolicy(policyId, request.getSnoozedUntil()));
    }

    @GetMapping("/needs")
    @EmitEvent(id = "INVENTORY_REPLENISHMENT_NEEDS_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @PreAuthorize("hasAuthority('inventory:on_hand:view')")
    @Operation(
            operationId = "listReplenishmentNeeds",
            summary = "Replenishment needs report",
            description = "Side-effect-free orderpoint evaluation of every active, non-snoozed replenishment"
                    + " policy: projected availability at the lead-time horizon, whether a scan would trigger,"
                    + " the multiple-rounded suggested quantity, and the projected stock-out deadline. Never"
                    + " creates or refreshes tasks — ops review before running the scan.",
            tags = {"Replenishment"})
    @ApiResponse(
            responseCode = "200",
            description = "Replenishment needs returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ReplenishmentNeedResponse.class))))
    public ResponseEntity<List<ReplenishmentNeedResponse>> getReplenishmentNeeds() {
        return ResponseEntity.ok(replenishmentService.getReplenishmentNeeds());
    }

    @PostMapping("/scan")
    @EmitEvent(id = "INVENTORY_REPLENISHMENT_SCAN_RUN", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:replenishment:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.REPLENISHMENT_MANAGE + "')")
    @Operation(
            operationId = "runReplenishmentScan",
            summary = "Run the batch replenishment scan",
            description = "Evaluates every replenishment policy against current on-hand and creates or refreshes"
                    + " batch-triggered replenishment tasks for below-minimum pick faces. Idempotent per"
                    + " (policy, day): repeated runs on the same day never create duplicate open tasks."
                    + " Returns a summary of the scan run.",
            tags = {"Replenishment"})
    @ApiResponse(
            responseCode = "200",
            description = "Scan completed; summary returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReplenishmentScanResultResponse.class)))
    public ResponseEntity<ReplenishmentScanResultResponse> runReplenishmentScan() {
        return ResponseEntity.ok(replenishmentService.runBatchReplenishmentScan());
    }
}
