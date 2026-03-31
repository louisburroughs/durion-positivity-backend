package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.dto.consumption.ConsumptionResponse;
import com.positivity.inventory.service.ConsumptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/consumption")
@RequiredArgsConstructor
@Tag(name = "Consumption", description = "Workorder parts consumption endpoints")
public class ConsumptionController {

    private final ConsumptionService consumptionService;

    @PostMapping
    @EmitEvent(id = "INVENTORY_WORKORDER_CONSUMPTION_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('inventory:adjustment:create')")
    @Operation(
            summary = "Consume picked items",
            description = "Consumes picked inventory for a workorder and records resulting stock movement")
    @ApiResponse(responseCode = "201", description = "Consumption recorded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConsumptionResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "User lacks required consumption authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "Consumption business rule validation failed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ConsumptionResponse> consumePickedItems(@Valid @RequestBody ConsumeItemsRequest request) {
        ConsumptionResponse response = consumptionService.consumePickedItems(request);
        return ResponseEntity.status(201).body(response);
    }
}
