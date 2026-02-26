package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.putaway.GeneratePutawayTasksRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayTaskResponse;
import com.positivity.inventory.service.PutawayGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/v1/inventory/putaway/tasks")
@RequiredArgsConstructor
@Tag(name = "Putaway", description = "Putaway task generation and claiming endpoints")
@PreAuthorize("hasAnyAuthority('inventory:availability:read','inventory:adjustment:create')")
public class PutawayController {

    private final PutawayGenerationService putawayGenerationService;

    @PostMapping("/generate")
    @EmitEvent(id = "INVENTORY_PUTAWAY_TASK_GENERATE", apiVersion = "1")
    @Operation(
            summary = "Generate putaway tasks",
            description = "Generates putaway tasks for received inventory lines.")
    @ApiResponse(
            responseCode = "201",
            description = "Putaway tasks generated",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = PutawayTaskResponse.class))))
    @ApiResponse(responseCode = "400", description = "Validation failure")
    public ResponseEntity<List<PutawayTaskResponse>> generateTasks(
            @Valid @RequestBody GeneratePutawayTasksRequest request) {
        List<PutawayTaskResponse> response = putawayGenerationService.generateTasksForReceipt(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(
            summary = "List available putaway tasks",
            description = "Returns all currently available putaway tasks.")
    @ApiResponse(
            responseCode = "200",
            description = "Available putaway tasks returned",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = PutawayTaskResponse.class))))
    public ResponseEntity<List<PutawayTaskResponse>> getAvailableTasks() {
        return ResponseEntity.ok(putawayGenerationService.getAvailableTasks());
    }

    @PostMapping("/{taskId}/claim")
    @EmitEvent(id = "INVENTORY_PUTAWAY_TASK_CLAIM", apiVersion = "1")
    @Operation(
            summary = "Claim a putaway task",
            description = "Claims an available putaway task for the current actor.")
    @ApiResponse(
            responseCode = "200",
            description = "Putaway task claimed",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PutawayTaskResponse.class)))
    @ApiResponse(responseCode = "404", description = "Putaway task not found")
    public ResponseEntity<PutawayTaskResponse> claimTask(
            @Parameter(description = "Putaway task identifier", required = true)
            @PathVariable String taskId,
            @Parameter(description = "Preferred actor identifier from gateway context")
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(description = "Fallback actor identifier from gateway context")
            @RequestHeader(value = "X-User", required = false) String gatewayUser) {
        String actor = (userId != null && !userId.isBlank())
                ? userId
                : ((gatewayUser != null && !gatewayUser.isBlank()) ? gatewayUser : "system");

        PutawayTaskResponse response = putawayGenerationService.claimTask(taskId, actor);
        return ResponseEntity.ok(response);
    }
}
