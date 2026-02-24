package com.positivity.inventory.internal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayExecutionResponse;
import com.positivity.inventory.service.PutawayExecuteService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/inventory/putaway")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('inventory:picking:manage')")
public class PutawayExecuteController {

    private final PutawayExecuteService putawayExecuteService;

    @PostMapping("/tasks/{taskId}/execute")
    @EmitEvent(id = "INVENTORY_PUTAWAY_EXECUTE", apiVersion = "1")
    public ResponseEntity<PutawayExecutionResponse> executePutaway(
            @PathVariable String taskId,
            @RequestBody PutawayExecutionRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User", required = false) String gatewayUser) {
        String actorId = (userId != null && !userId.isBlank())
                ? userId
                : ((gatewayUser != null && !gatewayUser.isBlank()) ? gatewayUser : "system");

        PutawayExecutionResponse response = putawayExecuteService.executePutaway(taskId, request, actorId);
        return ResponseEntity.ok(response);
    }
}