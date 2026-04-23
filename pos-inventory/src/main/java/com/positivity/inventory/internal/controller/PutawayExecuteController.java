package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayExecutionResponse;
import com.positivity.inventory.service.PutawayExecuteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                "inventory:putaway:execute" })
@RequestMapping("/v1/inventory/putaway")
@RequiredArgsConstructor
@Tag(name = "Putaway Execution", description = "Execute putaway tasks with validation and audit events")
public class PutawayExecuteController {

        private final PutawayExecuteService putawayExecuteService;

        @PostMapping("/tasks/{taskId}/execute")
        @EmitEvent(id = "INVENTORY_PUTAWAY_EXECUTE", apiVersion = "1")
        @PreAuthorize("hasAuthority('inventory:putaway:execute')")
        @Operation(summary = "Execute putaway task", description = "Executes a putaway task by moving SKU quantity from source location to destination location.", tags = {
                        "Putaway Execution" })
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Putaway executed successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PutawayExecutionResponse.class))),
                        @ApiResponse(responseCode = "400", description = "Bad request - invalid task ID or request payload"),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient authority"),
                        @ApiResponse(responseCode = "404", description = "Putaway task not found"),
                        @ApiResponse(responseCode = "422", description = "Validation failed for putaway execution")
        })
        public ResponseEntity<PutawayExecutionResponse> executePutaway(
                        @Parameter(description = "Putaway task identifier", required = true) @PathVariable String taskId,
                        @Parameter(description = "Putaway execution request payload", required = true) @Valid @RequestBody PutawayExecutionRequest request) {
                PutawayExecutionResponse response = putawayExecuteService.executePutaway(taskId, request);
                return ResponseEntity.ok(response);
        }
}
