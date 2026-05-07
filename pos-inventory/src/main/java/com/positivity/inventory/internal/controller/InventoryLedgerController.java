package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.dto.InventoryLedgerEntryDto;
import com.positivity.inventory.internal.dto.InventoryLedgerFilterParams;
import com.positivity.inventory.internal.dto.LedgerPage;
import com.positivity.inventory.service.InventoryLedgerService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/ledger")
@RequiredArgsConstructor
@Tag(name = "Inventory Ledger", description = "Inventory ledger read endpoints")
public class InventoryLedgerController {

    private final InventoryLedgerService inventoryLedgerService;

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:ledger:view"})
    @PreAuthorize("hasAuthority('inventory:ledger:view')")
    @Operation(
            operationId = "listInventoryLedger",
            summary = "List inventory ledger entries",
            description = "Returns inventory ledger entries using optional filters.",
            tags = {"Inventory Ledger"})
    @ApiResponse(
            responseCode = "200",
            description = "Ledger entries returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = LedgerPage.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request parameters",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required ledger view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<LedgerPage<InventoryLedgerEntryDto>> listLedgerEntries(
            @RequestParam(required = false) String productSku,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID storageLocationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            @RequestParam(required = false) UUID sourceTransactionId,
            @RequestParam(required = false) UUID workorderId,
            @RequestParam(required = false) UUID workorderLineId,
            @RequestParam(required = false) List<String> movementTypes,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false, defaultValue = "50") int pageSize) {
        InventoryLedgerFilterParams params = InventoryLedgerFilterParams.builder()
                .productSku(productSku)
                .locationId(locationId)
                .storageLocationId(storageLocationId)
                .dateFrom(dateFrom)
                .dateTo(dateTo)
                .sourceTransactionId(sourceTransactionId)
                .workorderId(workorderId)
                .workorderLineId(workorderLineId)
                .movementTypes(movementTypes)
                .pageToken(pageToken)
                .pageSize(pageSize)
                .build();

        return ResponseEntity.ok(inventoryLedgerService.listLedgerEntries(params));
    }

    @GetMapping("/{entryId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:ledger:view"})
    @PreAuthorize("hasAuthority('inventory:ledger:view')")
    @Operation(
            operationId = "getInventoryLedgerEntry",
            summary = "Get inventory ledger entry",
            description = "Returns a single inventory ledger entry by ID.",
            tags = {"Inventory Ledger"})
    @ApiResponse(
            responseCode = "200",
            description = "Ledger entry returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = InventoryLedgerEntryDto.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required ledger view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Ledger entry not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<InventoryLedgerEntryDto> getLedgerEntry(@PathVariable UUID entryId) {
        return ResponseEntity.ok(inventoryLedgerService.getLedgerEntry(entryId));
    }
}
