package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsRequest;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickedItemResponse;
import com.positivity.workorder.service.WorkorderPickFacadeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/workorders/{workorderId}")
@Tag(name = "Workorder Picked Items", description = "Browser-facing picked items and consume endpoints for workorder fulfillment")
public class WorkorderPickedItemsController {

    private final WorkorderPickFacadeService workorderPickFacadeService;

    public WorkorderPickedItemsController(WorkorderPickFacadeService workorderPickFacadeService) {
        this.workorderPickFacadeService = workorderPickFacadeService;
    }

    @GetMapping("/picked-items")
    @PreAuthorize("hasAuthority('inventory:pick_list:view')")
    @Operation(summary = "Get picked items for workorder")
    @ApiResponse(responseCode = "200", description = "Picked items retrieved successfully", content = @Content(schema = @Schema(implementation = WorkorderPickedItemResponse.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<WorkorderPickedItemResponse>> getPickedItems(
            @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable @NonNull UUID workorderId) {

        List<WorkorderPickedItemResponse> response = workorderPickFacadeService.getPickedItemsForWorkorder(workorderId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/picked-items:consume")
    @PreAuthorize("hasAuthority('workorder:parts:consume')")
    @EmitEvent(id = "WORKORDER_PICKED_ITEMS_CONSUME", apiVersion = "1")
    @Operation(summary = "Consume picked items into workorder")
    @ApiResponse(responseCode = "200", description = "Picked items consumed successfully", content = @Content(schema = @Schema(implementation = ConsumePickedItemsResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Workorder not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ConsumePickedItemsResponse> consumePickedItems(
            @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable @NonNull UUID workorderId,
            @RequestBody @Valid ConsumePickedItemsRequest request) {

        ConsumePickedItemsResponse response = workorderPickFacadeService.consumePickedItems(workorderId, request);
        return ResponseEntity.ok(response);
    }
}
