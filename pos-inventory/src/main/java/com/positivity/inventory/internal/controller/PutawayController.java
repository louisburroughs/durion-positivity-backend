package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.dto.putaway.GeneratePutawayTasksRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayTaskResponse;
import com.positivity.inventory.service.PutawayGenerationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/inventory/putaway/tasks")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('inventory:availability:read','inventory:adjustment:create')")
public class PutawayController {

    private final PutawayGenerationService putawayGenerationService;

    @PostMapping("/generate")
    public ResponseEntity<List<PutawayTaskResponse>> generateTasks(
            @Valid @RequestBody GeneratePutawayTasksRequest request) {
        List<PutawayTaskResponse> response = putawayGenerationService.generateTasksForReceipt(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<PutawayTaskResponse>> getAvailableTasks() {
        return ResponseEntity.ok(putawayGenerationService.getAvailableTasks());
    }

    @PostMapping("/{taskId}/claim")
    public ResponseEntity<PutawayTaskResponse> claimTask(
            @PathVariable String taskId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User", required = false) String gatewayUser) {
        String actor = (userId != null && !userId.isBlank())
                ? userId
                : ((gatewayUser != null && !gatewayUser.isBlank()) ? gatewayUser : "system");

        PutawayTaskResponse response = putawayGenerationService.claimTask(taskId, actor);
        return ResponseEntity.ok(response);
    }
}