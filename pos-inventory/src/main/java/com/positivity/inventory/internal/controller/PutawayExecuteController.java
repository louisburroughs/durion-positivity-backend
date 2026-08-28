package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayExecutionResponse;
import com.positivity.inventory.internal.putaway.service.PutawayExecuteService;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"inventory:putaway:execute"})
@RequestMapping("/v1/inventory/putaway")
@RequiredArgsConstructor
@Tag(name = "Putaway Execution", description = "Execute putaway tasks with validation and audit events")
public class PutawayExecuteController {

    private final PutawayExecuteService putawayExecuteService;

    @PostMapping("/tasks/{taskId}/execute")
    @EmitEvent(id = "INVENTORY_PUTAWAY_EXECUTE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.PUTAWAY_EXECUTE + "')")
    @Operation(
            operationId = "executePutaway",
            summary = "Execute Putaway Task",
            description = """
                    Executes a putaway move, posting paired PUTAWAY ledger entries that decrement on-hand at the \
                    staging source location and increment it at the destination, then marking the task COMPLETED \
                    with the actual destination recorded.
                    Use this tool when the clerk physically moves staged stock into storage; do not use \
                    generatePutawayTasks, which only creates tasks, and do not use claimPutawayTask, which only \
                    assigns one.
                    Preconditions: the task must exist, the source location must hold on-hand stock of the SKU, \
                    and the destination must be valid for the SKU and within capacity unless the matching \
                    override flag is set by a caller holding the corresponding putaway override permission; every \
                    override needs overrideReasonCode and overrideJustification, and a capacity override also \
                    needs approvedBy and must stay within the configured overfill tolerance.
                    Required inputs: taskId (UUID string) path parameter plus skuId, sourceLocationId, \
                    destinationLocationId and a positive quantity; the destination may differ from the task's \
                    suggestion, and the override fields are optional.
                    Emits an INVENTORY_PUTAWAY_EXECUTE event; on-hand shifts between the two locations \
                    immediately and the task leaves the executable pool.
                    Returns 404 when the task does not exist, 422 when the source lacks on-hand, the destination \
                    is invalid for the SKU or at capacity, or an override omits its audit fields, 403 when an \
                    override is requested without its permission, and 400 when taskId is not a valid UUID.
                    """,
            tags = {"Putaway Execution"})
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Putaway executed successfully",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = PutawayExecutionResponse.class))),
                @ApiResponse(responseCode = "400", description = "Bad request - invalid task ID or request payload"),
                @ApiResponse(responseCode = "403", description = "Forbidden - insufficient authority"),
                @ApiResponse(responseCode = "404", description = "Putaway task not found"),
                @ApiResponse(responseCode = "422", description = "Validation failed for putaway execution")
            })
    public ResponseEntity<PutawayExecutionResponse> executePutaway(
            @Parameter(description = "Putaway task identifier", required = true) @PathVariable String taskId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The clerk's scan data for the move, with optional business-rule overrides.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PutawayExecutionRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "Standard move without overrides",
                                                            value = """
                                                                    {"skuId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a41",
                                                                     "sourceLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a43",
                                                                     "destinationLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a44",
                                                                     "quantity":12}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PutawayExecutionRequest request) {
        PutawayExecutionResponse response = putawayExecuteService.executePutaway(taskId, request);
        return ResponseEntity.ok(response);
    }
}
