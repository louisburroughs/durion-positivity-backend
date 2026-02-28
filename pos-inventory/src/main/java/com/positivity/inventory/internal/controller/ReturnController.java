package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.InventoryErrorResponse;
import com.positivity.inventory.internal.dto.returns.ReturnItemsRequest;
import com.positivity.inventory.internal.dto.returns.ReturnResponse;
import com.positivity.inventory.service.ReturnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/returns")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('inventory:availability:read','inventory:adjustment:create')")
@Tag(name = "Returns", description = "Inventory return-to-stock endpoints")
public class ReturnController {

    private final ReturnService returnService;

    @PostMapping
    @EmitEvent(id = "INVENTORY_RETURN_TO_STOCK_CREATE", apiVersion = "1")
    @Operation(
            summary = "Return items to stock",
            description = "Returns issued parts to inventory and records resulting return movement")
    @ApiResponse(responseCode = "201", description = "Return recorded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReturnResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "User lacks required return authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "Return quantity exceeds allowable amount", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class)))
    public ResponseEntity<ReturnResponse> returnItemsToStock(@Valid @RequestBody ReturnItemsRequest request) {
        ReturnResponse response = returnService.returnItemsToStock(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
