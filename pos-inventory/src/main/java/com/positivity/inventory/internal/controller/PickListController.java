package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.picklist.ConfirmPickTaskRequest;
import com.positivity.inventory.internal.dto.picklist.CreatePickListRequest;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.dto.picklist.PickTaskResponse;
import com.positivity.inventory.internal.dto.picklist.UpdatePickListStatusRequest;
import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.service.PickListService;
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
public class PickListController {

    private final PickListService pickListService;

    @PostMapping
    @EmitEvent(id = "INVENTORY_PICK_LIST_CREATE", apiVersion = "1")
    public ResponseEntity<PickListResponse> createPickList(@Valid @RequestBody CreatePickListRequest request) {
        PickListResponse response = pickListService.createPickList(request);
        return ResponseEntity.created(URI.create("/v1/inventory/pick-lists/" + response.getPickListId()))
                .body(response);
    }

    @GetMapping("/{pickListId}")
    public ResponseEntity<PickListResponse> getPickList(@PathVariable UUID pickListId) {
        return ResponseEntity.ok(pickListService.getPickList(pickListId));
    }

    @GetMapping
    public ResponseEntity<List<PickListResponse>> getPickListsForWorkorder(@RequestParam UUID workorderId) {
        return ResponseEntity.ok(pickListService.getPickListsForWorkorder(workorderId));
    }

    @PostMapping("/{pickListId}/release")
    @EmitEvent(id = "INVENTORY_PICK_LIST_RELEASE", apiVersion = "1")
    public ResponseEntity<PickListResponse> releasePickList(@PathVariable UUID pickListId) {
        return ResponseEntity.ok(pickListService.releasePickList(pickListId));
    }

    @PostMapping("/{pickListId}/tasks/{taskId}/confirm")
    @EmitEvent(id = "INVENTORY_PICK_TASK_CONFIRM", apiVersion = "1")
    public ResponseEntity<PickTaskResponse> confirmPickTask(
            @PathVariable UUID pickListId,
            @PathVariable UUID taskId,
            @Valid @RequestBody ConfirmPickTaskRequest request) {
        return ResponseEntity.ok(pickListService.confirmPickTask(
                pickListId,
                taskId,
                request.getScannedSkuId(),
                request.getScannedLocationId(),
                request.getQuantityPicked()));
    }

    @GetMapping("/{pickListId}/tasks")
    public ResponseEntity<List<PickTaskResponse>> getPickTasksForPickList(@PathVariable UUID pickListId) {
        return ResponseEntity.ok(pickListService.getPickTasksForPickList(pickListId));
    }

    @PatchMapping("/{pickListId}/status")
    @EmitEvent(id = "INVENTORY_PICK_LIST_STATUS_UPDATE", apiVersion = "1")
    public ResponseEntity<PickListResponse> updatePickListStatus(
            @PathVariable UUID pickListId,
            @Valid @RequestBody UpdatePickListStatusRequest request) {
        PickListStatus status = request.getStatus();
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        return ResponseEntity.ok(pickListService.updatePickListStatus(pickListId, status));
    }

    @DeleteMapping("/{pickListId}")
    @EmitEvent(id = "INVENTORY_PICK_LIST_CANCEL", apiVersion = "1")
    public ResponseEntity<Void> cancelPickList(@PathVariable UUID pickListId) {
        pickListService.cancelPickList(pickListId);
        return ResponseEntity.noContent().build();
    }
}