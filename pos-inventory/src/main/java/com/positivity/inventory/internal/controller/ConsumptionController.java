package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.dto.consumption.ConsumptionResponse;
import com.positivity.inventory.service.ConsumptionService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"inventory:adjustment:create"})
@RequestMapping("/v1/inventory/consumption")
@RequiredArgsConstructor
@Tag(name = "Consumption", description = "Workorder parts consumption endpoints")
public class ConsumptionController {

    private final ConsumptionService consumptionService;

    @PostMapping
    @EmitEvent(id = "INVENTORY_WORKORDER_CONSUMPTION_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('inventory:adjustment:create')")
    @Operation(
            operationId = "consumePickedItems",
            summary = "Consume picked items",
            description = """
                    Consumes picked items against a workorder, posting a negative WORKORDER_CONSUMPTION ledger \
                    entry per line and closing the originating located HARD allocations with ALLOCATION_RELEASED \
                    entries in the same transaction.
                    Use this tool after confirmPickTask has marked the tasks PICKED; do not use \
                    createStockMovement with ISSUE for workorder parts, which bypasses allocation closure, and do \
                    not use cancelReservation, which releases demand without consuming stock.
                    Preconditions: every referenced pick task must exist and be PICKED, each line quantity must \
                    not exceed the task's picked quantity, and a LOT-tracked SKU needs a lot — the line's \
                    lotNumber override or the lot recorded at pick confirmation.
                    Required inputs: workorderId (UUID) and items, each with pickTaskId (UUID), skuId (UUID) and a \
                    positive quantity; pickListId and per-line lotNumber are optional, and an absent or empty \
                    items list yields a no-op response.
                    Emits an INVENTORY_WORKORDER_CONSUMPTION_CREATE event and publishes a ConsumptionRecordedV1 \
                    fact carrying engine-derived costs; allocations close in sourcing-strategy order (FIFO \
                    oldest-first by default), each release capped at the allocation's un-released remainder, and \
                    the reservation's allocated quantity is reduced by what was consumed.
                    Returns 404 when a pick task is unknown, 422 with WORKORDER_CONSUMPTION_ERROR when a task is \
                    not PICKED or a quantity exceeds what was picked, and 422 with LOT_NUMBER_REQUIRED, \
                    LOT_UNKNOWN or LOT_NOT_AVAILABLE for lot failures.
                    """,
            tags = {"Consumption"})
    @ApiResponse(
            responseCode = "201",
            description = "Consumption recorded",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ConsumptionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required consumption authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Consumption business rule validation failed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ConsumptionResponse> consumePickedItems(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The workorder consuming the parts and the picked lines (pick task, SKU,"
                                    + " quantity, optional lot override) to consume.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Consume one picked line", value = """
                                                                    {"workorderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "pickListId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
                                                                     "items":[{"pickTaskId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
                                                                               "skuId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04",
                                                                               "quantity":4,
                                                                               "lotNumber":"LOT-2026-A"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ConsumeItemsRequest request) {
        ConsumptionResponse response = consumptionService.consumePickedItems(request);
        return ResponseEntity.status(201).body(response);
    }
}
