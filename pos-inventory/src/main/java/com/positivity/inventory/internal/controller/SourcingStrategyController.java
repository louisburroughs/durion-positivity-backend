package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.sourcing.SourcingStrategyConfigRequest;
import com.positivity.inventory.internal.dto.sourcing.SourcingStrategyConfigResponse;
import com.positivity.inventory.service.SourcingStrategyConfigService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for sourcing-strategy configuration (odoo-parity H1, issue
 * #1037). Guarded by {@code inventory:location:admin} — sourcing strategy is
 * location-topology administration, no new permission string is introduced.
 */
@RestController
@RequestMapping("/v1/inventory/sourcing-strategies")
@RequiredArgsConstructor
@Tag(name = "Sourcing Strategies", description = "Sourcing/removal strategy configuration endpoints")
public class SourcingStrategyController {

    private final SourcingStrategyConfigService sourcingStrategyConfigService;

    @GetMapping
    @EmitEvent(id = "INVENTORY_SOURCING_STRATEGY_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @PreAuthorize("hasAuthority('inventory:location:admin')")
    @Operation(
            summary = "List sourcing strategy configurations",
            description = "Lists all sourcing strategy configuration rows (active and inactive), ordered by scope."
                    + " Resolution precedence at decision time is SKU_CATEGORY, then SITE, then DEFAULT, then the"
                    + " platform default FIFO.",
            tags = {"Sourcing Strategies"})
    @ApiResponse(
            responseCode = "200",
            description = "Sourcing strategy configurations returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = SourcingStrategyConfigResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required permission",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<SourcingStrategyConfigResponse>> listConfigs() {
        return ResponseEntity.ok(sourcingStrategyConfigService.listConfigs());
    }

    @PutMapping
    @EmitEvent(id = "INVENTORY_SOURCING_STRATEGY_UPSERT", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @PreAuthorize("hasAuthority('inventory:location:admin')")
    @Operation(
            summary = "Upsert sourcing strategy configuration",
            description = "Creates or updates the sourcing strategy for one scope (SKU_CATEGORY, SITE, or DEFAULT)"
                    + " and reactivates it. At most one row exists per scope.",
            tags = {"Sourcing Strategies"})
    @ApiResponse(
            responseCode = "200",
            description = "Sourcing strategy configuration upserted",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SourcingStrategyConfigResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required permission",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SourcingStrategyConfigResponse> upsertConfig(
            @Valid @RequestBody SourcingStrategyConfigRequest request) {
        return ResponseEntity.ok(sourcingStrategyConfigService.upsertConfig(request));
    }

    @DeleteMapping("/{configId}")
    @EmitEvent(id = "INVENTORY_SOURCING_STRATEGY_DEACTIVATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @PreAuthorize("hasAuthority('inventory:location:admin')")
    @Operation(
            summary = "Deactivate sourcing strategy configuration",
            description = "Soft delete: marks the configuration row inactive so it stops participating in strategy"
                    + " resolution; the row is never removed.",
            tags = {"Sourcing Strategies"})
    @ApiResponse(
            responseCode = "200",
            description = "Sourcing strategy configuration deactivated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SourcingStrategyConfigResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required permission",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Sourcing strategy configuration not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SourcingStrategyConfigResponse> deactivateConfig(
            @Parameter(description = "Sourcing strategy configuration identifier", required = true) @PathVariable
                    UUID configId) {
        return ResponseEntity.ok(sourcingStrategyConfigService.deactivateConfig(configId));
    }
}
