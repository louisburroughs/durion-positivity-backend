package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.inventory.internal.dto.picklist.ConfirmPickTaskRequest;
import com.positivity.inventory.internal.dto.picklist.CreatePickListRequest;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.dto.picklist.PickTaskResponse;
import com.positivity.inventory.internal.dto.picklist.UpdatePickListStatusRequest;
import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.service.PickListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/pick-lists")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('inventory:availability:read','inventory:adjustment:create')")
@Tag(name = "Pick Lists", description = "Pick list and pick task management endpoints")
public class PickListController {

    private final PickListService pickListService;

    @PostMapping
    @EmitEvent(id = "INVENTORY_PICK_LIST_CREATE", apiVersion = "1")
    @Operation(summary = "Create pick list", description = "Creates a pick list for a workorder and returns generated pick tasks")
    @ApiResponse(responseCode = "201", description = "Pick list created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PickListResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "User lacks required pick list authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PickListResponse> createPickList(@Valid @RequestBody CreatePickListRequest request) {
        PickListResponse response = pickListService.createPickList(request);
        return ResponseEntity.created(URI.create("/v1/inventory/pick-lists/" + response.getPickListId()))
                .body(response);
    }

    @GetMapping("/{pickListId}")
    @Operation(summary = "Get pick list", description = "Retrieves a pick list by identifier")
    @ApiResponse(responseCode = "200", description = "Pick list returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PickListResponse.class)))
    @ApiResponse(responseCode = "403", description = "User lacks required pick list authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Pick list not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PickListResponse> getPickList(
            @Parameter(description = "Pick list identifier", required = true)
            @PathVariable UUID pickListId) {
        return ResponseEntity.ok(pickListService.getPickList(pickListId));
    }

    @GetMapping
    @Operation(summary = "List pick lists for workorder", description = "Returns pick lists linked to the provided workorder identifier")
    @ApiResponse(responseCode = "200", description = "Pick lists returned", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = PickListResponse.class))))
    @ApiResponse(responseCode = "400", description = "Invalid workorder identifier", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "User lacks required pick list authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<PickListResponse>> getPickListsForWorkorder(
            @Parameter(description = "Workorder identifier", required = true)
            @RequestParam UUID workorderId) {
        return ResponseEntity.ok(pickListService.getPickListsForWorkorder(workorderId));
    }

    @PostMapping("/{pickListId}/release")
    @EmitEvent(id = "INVENTORY_PICK_LIST_RELEASE", apiVersion = "1")
    @Operation(summary = "Release pick list", description = "Releases a pick list so picking tasks can be executed")
    @ApiResponse(responseCode = "200", description = "Pick list released", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PickListResponse.class)))
    @ApiResponse(responseCode = "403", description = "User lacks required pick list authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Pick list not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PickListResponse> releasePickList(
            @Parameter(description = "Pick list identifier", required = true)
            @PathVariable UUID pickListId) {
        return ResponseEntity.ok(pickListService.releasePickList(pickListId));
    }

    @PostMapping("/{pickListId}/tasks/{taskId}/confirm")
    @EmitEvent(id = "INVENTORY_PICK_TASK_CONFIRM", apiVersion = "1")
    @Operation(summary = "Confirm pick task", description = "Confirms a pick task using scanned SKU and location data")
    @ApiResponse(responseCode = "200", description = "Pick task confirmed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PickTaskResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "User lacks required pick task authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Pick list or pick task not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "Pick confirmation failed business validation", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PickTaskResponse> confirmPickTask(
            @Parameter(description = "Pick list identifier", required = true)
            @PathVariable UUID pickListId,
            @Parameter(description = "Pick task identifier", required = true)
            @PathVariable UUID taskId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Pick task confirmation payload",
                    content = @Content(schema = @Schema(implementation = ConfirmPickTaskRequest.class)))
            @Valid @RequestBody ConfirmPickTaskRequest request) {
        return ResponseEntity.ok(pickListService.confirmPickTask(
                pickListId,
                taskId,
                request.getScannedSkuId(),
                request.getScannedLocationId(),
                request.getQuantityPicked()));
    }

    @GetMapping("/{pickListId}/tasks")
    @Operation(summary = "List pick tasks for pick list", description = "Returns all pick tasks associated with a pick list")
    @ApiResponse(responseCode = "200", description = "Pick tasks returned", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = PickTaskResponse.class))))
    @ApiResponse(responseCode = "403", description = "User lacks required pick list authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Pick list not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<PickTaskResponse>> getPickTasksForPickList(
            @Parameter(description = "Pick list identifier", required = true)
            @PathVariable UUID pickListId) {
        return ResponseEntity.ok(pickListService.getPickTasksForPickList(pickListId));
    }

    @PatchMapping("/{pickListId}/status")
    @EmitEvent(id = "INVENTORY_PICK_LIST_STATUS_UPDATE", apiVersion = "1")
    @Operation(summary = "Update pick list status", description = "Updates pick list lifecycle status")
    @ApiResponse(responseCode = "200", description = "Pick list status updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PickListResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "User lacks required pick list authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Pick list not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PickListResponse> updatePickListStatus(
            @Parameter(description = "Pick list identifier", required = true)
            @PathVariable UUID pickListId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Pick list status update payload",
                    content = @Content(schema = @Schema(implementation = UpdatePickListStatusRequest.class)))
            @Valid @RequestBody UpdatePickListStatusRequest request) {
        PickListStatus status = request.getStatus();
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        return ResponseEntity.ok(pickListService.updatePickListStatus(pickListId, status));
    }

    @DeleteMapping("/{pickListId}")
    @EmitEvent(id = "INVENTORY_PICK_LIST_CANCEL", apiVersion = "1")
    @Operation(summary = "Cancel pick list", description = "Cancels a pick list and marks pending tasks as cancelled")
    @ApiResponse(responseCode = "204", description = "Pick list cancelled")
    @ApiResponse(responseCode = "403", description = "User lacks required pick list authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Pick list not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> cancelPickList(
            @Parameter(description = "Pick list identifier", required = true)
            @PathVariable UUID pickListId) {
        pickListService.cancelPickList(pickListId);
        return ResponseEntity.noContent().build();
    }
}
