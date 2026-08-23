package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.dto.pick.CompletePickTaskRequest;
import com.positivity.workorder.internal.dto.pick.ConfirmPickLineRequest;
import com.positivity.workorder.internal.dto.pick.ResolveScanRequest;
import com.positivity.workorder.internal.dto.pick.ResolveScanResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickListResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickTaskResponse;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.service.WorkorderPickFacadeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
@Tag(
        name = "Workorder Pick Facade",
        description = "Browser-facing pick list and pick task endpoints for workorder fulfillment")
public class WorkorderPickFacadeController {

    private final WorkorderPickFacadeService workorderPickFacadeService;

    public WorkorderPickFacadeController(WorkorderPickFacadeService workorderPickFacadeService) {
        this.workorderPickFacadeService = workorderPickFacadeService;
    }

    @GetMapping("/pick-list")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:pick_list:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.INVENTORY_PICK_LIST_VIEW + "')")
    @Operation(operationId = "getWorkorderPickList", summary = "Get Pick List for Workorder", description = """
                    Returns the workorder's primary pick list header from the local inventory replica so parts \
                    can be staged and fulfilled.
                    Use this tool for the pick list summary; use getPickTasks instead for the individual pick lines that \
                    guide execution.
                    The full sequence for a workorder's parts: promoteEstimate creates the workorder and asks \
                    pos-inventory to reserve each part line and generate this pick list; getPickTasks returns \
                    the resulting tasks; resolvePickScan then confirmPickLine (or completePickTask) records \
                    what was picked; consumeWorkorderPickedItems consumes the picked tasks, which moves stock \
                    and raises the part line's quantityConsumed. A part the site cannot cover is answered with \
                    a backorder id on the workorder's part line instead of a pick task.
                    The generated list arrives in DRAFT and can be picked as-is; releasing it \
                    (POST /v1/inventory/pick-lists/{id}/release) marks it READY_TO_PICK for the floor.
                    Preconditions: a pick list replica must already exist for the workorder, replicated from \
                    pos-inventory facts.
                    Required inputs: workorderId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only replica projection.
                    Returns 404 when no pick list exists for the workorder.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Pick list retrieved successfully",
            content = @Content(schema = @Schema(implementation = WorkorderPickListResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Workorder not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<WorkorderPickListResponse> getWorkorderPickList(
            @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    @NonNull
                    UUID workorderId) {

        WorkorderPickListResponse response = workorderPickFacadeService.getPickListForWorkorder(workorderId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/pick-list/tasks")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:pick_list:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.INVENTORY_PICK_LIST_VIEW + "')")
    @Operation(operationId = "getPickTasks", summary = "Get Pick Tasks for Workorder", description = """
                    Returns the pick tasks of the workorder's primary pick list in sort order, each with its \
                    SKU, location, required and picked quantities, and status.
                    Use this tool to drive pick execution line by line; use getWorkorderPickList instead for the list header and \
                    getPickedItems for what has already been picked.
                    Preconditions: none. A workorder with no pick list - a labour-only job, or a promoted job \
                    whose list has not been generated yet - answers 200 with an empty array rather than 404, so \
                    "nothing to pick" never has to be handled as an error.
                    Promoting an estimate generates the pick list for the workorder's part lines, so tasks \
                    normally appear without any further call; use getWorkorderPickList when you need to know \
                    whether a list exists at all.
                    Required inputs: workorderId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only replica projection.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Pick tasks retrieved successfully; empty when the workorder has no pick list",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = WorkorderPickTaskResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<WorkorderPickTaskResponse>> getPickTasks(
            @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    @NonNull
                    UUID workorderId) {

        List<WorkorderPickTaskResponse> response = workorderPickFacadeService.getPickTasksForWorkorder(workorderId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/pick-tasks/{pickTaskId}:resolve-scan")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:pick_list:execute"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.INVENTORY_PICK_LIST_EXECUTE + "')")
    @EmitEvent(id = "WORKORDER_PICK_FACADE_RESOLVE_SCAN", apiVersion = "1")
    @Operation(operationId = "resolvePickScan", summary = "Resolve Scan Against Pick Task", description = """
                    Checks a scanned SKU and location against a pick task's expected SKU and location, returning \
                    matched plus a matchStatus of MATCHED, SKU_MISMATCH, LOCATION_MISMATCH, or NO_MATCH.
                    Use this tool to validate a barcode scan before confirmPickLine; it performs no confirmation \
                    itself, so do not treat a match as a recorded pick.
                    Preconditions: the pick task must exist on the workorder's pick list replica.
                    Required inputs: workorderId and pickTaskId (UUIDs) as path parameters, plus scannedSkuId \
                    and scannedLocationId (UUIDs) in the body.
                    Emits a WORKORDER_PICK_FACADE_RESOLVE_SCAN audit event; no pick state changes — the check is \
                    purely evaluative.
                    Returns 404 when the workorder has no pick list or the pick task is not on it.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Scan resolved successfully",
            content = @Content(schema = @Schema(implementation = ResolveScanResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Workorder or pick task not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ResolveScanResponse> resolveScan(
            @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    @NonNull
                    UUID workorderId,
            @Parameter(description = "Pick task ID", required = true, example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    @NonNull
                    UUID pickTaskId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Scanned SKU and location to check against the pick task's expectation.",
                            required = true,
                            content =
                                    @Content(
                                            schema = @Schema(implementation = ResolveScanRequest.class),
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Scan",
                                                            value = """
                                                                    {"scannedSkuId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aa1",
                                                                     "scannedLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aa2"}
                                                                    """)))
                    @RequestBody
                    @Valid
                    ResolveScanRequest request) {

        ResolveScanResponse response = workorderPickFacadeService.resolveScan(workorderId, pickTaskId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/pick-tasks/{pickTaskId}/lines/{pickLineId}:confirm")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:pick_list:execute"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.INVENTORY_PICK_LIST_EXECUTE + "')")
    @EmitEvent(id = "WORKORDER_PICK_FACADE_CONFIRM_LINE", apiVersion = "1")
    @Operation(operationId = "confirmPickLine", summary = "Confirm Pick Line Quantity", description = """
                    Queues asynchronous confirmation of a picked quantity on one pick line over the Kafka \
                    command feed per ADR-0044; the response carries status PENDING, and the inventory \
                    pick-task-updated fact later updates the local replica.
                    Use this tool after resolvePickScan validates the scan; do not use completePickTask, which \
                    confirms the full remaining required quantity in one step.
                    Preconditions: the pick task must exist on the workorder's pick list replica, and pickLineId \
                    must equal pickTaskId in the current single-line model.
                    Required inputs: workorderId, pickTaskId, and pickLineId (UUIDs) as path parameters, plus \
                    quantityPicked (integer) in the body.
                    Emits a WORKORDER_PICK_FACADE_CONFIRM_LINE event and publishes a pick-confirm command; \
                    callers must poll getPickTasks to observe the applied quantity.
                    Returns 202 with status PENDING when the confirmation is queued, 400 when pickLineId does \
                    not match pickTaskId, 404 when the pick list or task is missing, and 503 when the command \
                    feed is unavailable.
                    """)
    @ApiResponse(
            responseCode = "202",
            description = "Confirmation queued; poll the pick tasks for the applied quantity (status PENDING).",
            content = @Content(schema = @Schema(implementation = WorkorderPickTaskResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Workorder, pick task, or pick line not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Scan mismatch or domain validation error from pos-inventory",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<WorkorderPickTaskResponse> confirmPickLine(
            @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    @NonNull
                    UUID workorderId,
            @Parameter(description = "Pick task ID", required = true, example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    @NonNull
                    UUID pickTaskId,
            @Parameter(description = "Pick line ID", required = true, example = "550e8400-e29b-41d4-a716-446655440002")
                    @PathVariable
                    @NonNull
                    UUID pickLineId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Quantity actually picked for the line being confirmed.",
                            required = true,
                            content =
                                    @Content(
                                            schema = @Schema(implementation = ConfirmPickLineRequest.class),
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Confirm two units",
                                                            value = "{\"quantityPicked\":2}")))
                    @RequestBody
                    @Valid
                    ConfirmPickLineRequest request) {

        WorkorderPickTaskResponse response =
                workorderPickFacadeService.confirmPickLine(workorderId, pickTaskId, pickLineId, request);
        return ResponseEntity.accepted().body(response);
    }

    @PostMapping("/pick-tasks/{pickTaskId}:complete")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:pick_list:execute"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.INVENTORY_PICK_LIST_EXECUTE + "')")
    @EmitEvent(id = "WORKORDER_PICK_FACADE_COMPLETE_TASK", apiVersion = "1")
    @Operation(operationId = "completePickTask", summary = "Complete a Workorder Pick Task", description = """
                    Queues asynchronous completion of a pick task by confirming its full required quantity over \
                    the Kafka command feed per ADR-0044; a task with nothing left to pick is returned as-is.
                    Use this tool to close out a pick task in one step; use confirmPickLine instead to record a \
                    partial picked quantity.
                    Preconditions: the pick task must exist on the workorder's pick list replica.
                    Required inputs: workorderId and pickTaskId (UUIDs) as path parameters; the body is optional \
                    and may carry a completion reason.
                    Emits a WORKORDER_PICK_FACADE_COMPLETE_TASK event and publishes a pick-confirm command for \
                    the remaining quantity; callers must poll getPickTasks to observe the applied state.
                    Returns 202 with status PENDING while the command is in flight, 200 with the current state \
                    when the task is already complete, 404 when the pick list or task is missing, and 503 when \
                    the command feed is unavailable.
                    """)
    @ApiResponse(
            responseCode = "202",
            description = "Completion queued; poll the pick tasks for the applied state (status PENDING).",
            content = @Content(schema = @Schema(implementation = WorkorderPickTaskResponse.class)))
    @ApiResponse(
            responseCode = "200",
            description = "Task already complete; current state returned.",
            content = @Content(schema = @Schema(implementation = WorkorderPickTaskResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Workorder or pick task not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<WorkorderPickTaskResponse> completePickTask(
            @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    @NonNull
                    UUID workorderId,
            @Parameter(description = "Pick task ID", required = true, example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    @NonNull
                    UUID pickTaskId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = false,
                            description = "Optional completion details; omit or send {} if no reason is provided.",
                            content =
                                    @Content(
                                            schema = @Schema(implementation = CompletePickTaskRequest.class),
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "With reason",
                                                            value = "{\"reason\":\"Staged at bay 3\"}")))
                    @RequestBody(required = false)
                    @Valid
                    CompletePickTaskRequest request) {

        WorkorderPickTaskResponse response = workorderPickFacadeService.completePickTask(
                workorderId, pickTaskId, request != null ? request : new CompletePickTaskRequest());
        return WorkorderPickFacadeService.STATUS_PENDING.equals(response.getStatus())
                ? ResponseEntity.accepted().body(response)
                : ResponseEntity.ok(response);
    }
}
