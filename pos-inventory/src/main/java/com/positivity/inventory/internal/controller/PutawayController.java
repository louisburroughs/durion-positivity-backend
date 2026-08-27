package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.putaway.GeneratePutawayTasksRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayTaskResponse;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.service.PutawayGenerationService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/putaway/tasks")
@RequiredArgsConstructor
@Tag(name = "Putaway", description = "Putaway task generation and claiming endpoints")
public class PutawayController {

    private final PutawayGenerationService putawayGenerationService;

    @PostMapping("/generate")
    @EmitEvent(id = "INVENTORY_PUTAWAY_TASK_GENERATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:putaway:generate"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.PUTAWAY_GENERATE + "')")
    @Operation(
            operationId = "generatePutawayTasks",
            summary = "Generate Putaway Tasks",
            description = """
                    Generates one UNASSIGNED putaway task per received line of a goods receipt, sourced from the \
                    staging location, with the suggested destination resolved by the highest-priority enabled \
                    putaway rule or the default location when no rule exists.
                    Use this tool once staged goods need storage assignments; do not use executePutaway, which \
                    performs the physical move for one task, and do not use claimPutawayTask, which assigns an \
                    existing task to a worker.
                    Preconditions: the goods receipt named by sourceReceiptId must exist; putaway rules are \
                    optional.
                    Required inputs: sourceReceiptId (UUID string) plus either lineItems, each naming productId \
                    (UUID string) and a quantity of at least 1, or the legacy productId/quantity pair, but never \
                    both forms together.
                    Emits an INVENTORY_PUTAWAY_TASK_GENERATE event; the tasks are created UNASSIGNED and no stock \
                    moves until executePutaway runs.
                    Returns 404 when the goods receipt does not exist, 400 when both line forms are supplied, \
                    neither is supplied, an id is not a valid UUID, or a quantity is below 1, and 422 when the \
                    receipt is not booked into the staging location (RECEIPT_NOT_STAGED), when no enabled rule \
                    matches a line and no ANY fallback rule exists (NO_PUTAWAY_RULE_MATCH), or when the resolved \
                    destination is not physically fit for the line's catalog class \
                    (LOCATION_NOT_VALID_FOR_SKU).
                    """,
            tags = {"Putaway"})
    @ApiResponse(
            responseCode = "201",
            description = "Putaway tasks generated",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = PutawayTaskResponse.class))))
    @ApiResponse(responseCode = "400", description = "Validation failure")
    @ApiResponse(
            responseCode = "404",
            description = "Goods receipt not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Receipt not staged, no matching putaway rule, or destination not fit for the SKU",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<PutawayTaskResponse>> generateTasks(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The source goods receipt and the received lines needing storage.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = GeneratePutawayTasksRequest.class),
                                            examples = @ExampleObject(name = "Two received lines", value = """
                                                                    {"sourceReceiptId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a40",
                                                                     "lineItems":[
                                                                       {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a41","quantity":12},
                                                                       {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a42","quantity":4}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    GeneratePutawayTasksRequest request) {
        List<PutawayTaskResponse> response = putawayGenerationService.generateTasksForReceipt(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:putaway:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.PUTAWAY_VIEW + "')")
    @Operation(
            operationId = "listPutawayTasks",
            summary = "List Available Putaway Tasks",
            description = """
                    Returns every UNASSIGNED putaway task, each with its product, quantity, staging source and \
                    suggested destination.
                    Use this tool to find claimable putaway work before claimPutawayTask; tasks already ASSIGNED \
                    or COMPLETED never appear here, so do not use it to check a specific task's progress.
                    Preconditions: none; tasks exist only after generatePutawayTasks has run for a receipt.
                    Required inputs: none; locationId and storageLocationId optionally scope the list by source \
                    location, and storageLocationId wins when both are given.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array when no unassigned tasks match, so an empty result is not an \
                    error condition.
                    """,
            tags = {"Putaway"})
    @ApiResponse(
            responseCode = "200",
            description = "Available putaway tasks returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = PutawayTaskResponse.class))))
    public ResponseEntity<List<PutawayTaskResponse>> getAvailableTasks(
            @Parameter(description = "Location identifier") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Storage location identifier") @RequestParam(required = false)
                    UUID storageLocationId) {
        return ResponseEntity.ok(putawayGenerationService.getAvailableTasks(locationId, storageLocationId));
    }

    @PostMapping("/{taskId}/claim")
    @EmitEvent(id = "INVENTORY_PUTAWAY_TASK_CLAIM", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:putaway:claim"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.PUTAWAY_CLAIM + "')")
    @Operation(
            operationId = "claimPutawayTask",
            summary = "Claim Putaway Task",
            description = """
                    Claims a putaway task for the calling user, locking the row and marking it ASSIGNED with the \
                    caller as assignee.
                    Use this tool after finding a task via listPutawayTasks and before executePutaway; do not use \
                    executePutaway to claim, execution completes the task regardless of assignee.
                    Preconditions: the task must exist; the service applies no status gate, so claiming an \
                    already ASSIGNED task silently reassigns it to the caller.
                    Required inputs: taskId (UUID string) path parameter; there is no request body.
                    Emits an INVENTORY_PUTAWAY_TASK_CLAIM event; no stock moves and the task's suggested \
                    destination is unchanged.
                    Returns 404 when no putaway task exists for the supplied id, and 400 when taskId is not a \
                    valid UUID.
                    """,
            tags = {"Putaway"})
    @ApiResponse(
            responseCode = "200",
            description = "Putaway task claimed",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PutawayTaskResponse.class)))
    @ApiResponse(responseCode = "404", description = "Putaway task not found")
    public ResponseEntity<PutawayTaskResponse> claimTask(
            @Parameter(description = "Putaway task identifier", required = true) @PathVariable String taskId) {
        String actor = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("No current user"));

        PutawayTaskResponse response = putawayGenerationService.claimTask(taskId, actor);
        return ResponseEntity.ok(response);
    }
}
