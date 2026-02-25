package com.positivity.inventory.internal.controller;

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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/replenishment")
@RequiredArgsConstructor
@Tag(name = "Replenishment", description = "Replenishment task and policy endpoints")
@PreAuthorize("hasAnyAuthority('inventory:stock:view','inventory:stock:adjust','inventory:availability:read','inventory:adjustment:create')")
public class ReplenishmentController {

    private final ReplenishmentService replenishmentService;

    @GetMapping("/tasks")
    @Operation(
            summary = "List replenishment tasks",
            description = "Returns replenishment tasks that should be fulfilled.")
    @ApiResponse(
            responseCode = "200",
            description = "Replenishment tasks returned",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = ReplenishmentTaskResponse.class))))
    public ResponseEntity<List<ReplenishmentTaskResponse>> getReplenishmentTasks() {
        return ResponseEntity.ok(replenishmentService.getReplenishmentTasks());
    }

    @GetMapping("/policies")
    @Operation(
            summary = "List replenishment policies",
            description = "Returns configured replenishment policies.")
    @ApiResponse(
            responseCode = "200",
            description = "Replenishment policies returned",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = ReplenishmentPolicyResponse.class))))
    public ResponseEntity<List<ReplenishmentPolicyResponse>> getReplenishmentPolicies() {
        return ResponseEntity.ok(replenishmentService.getReplenishmentPolicies());
    }

    @PostMapping("/policies")
    @Operation(
            summary = "Create replenishment policy",
            description = "Creates a replenishment policy used to generate replenishment tasks.")
    @ApiResponse(
            responseCode = "201",
            description = "Replenishment policy created",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ReplenishmentPolicyResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure")
    public ResponseEntity<ReplenishmentPolicyResponse> createReplenishmentPolicy(
            @Valid @RequestBody CreateReplenishmentPolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(replenishmentService.createReplenishmentPolicy(request));
    }
}
