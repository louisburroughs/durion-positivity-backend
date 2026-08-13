package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.cyclecount.CountEntryResponse;
import com.positivity.inventory.internal.dto.cyclecount.CountResponse;
import com.positivity.inventory.internal.dto.cyclecount.CycleCountTaskResponse;
import com.positivity.inventory.internal.dto.cyclecount.InterferingMovementResponse;
import com.positivity.inventory.internal.dto.cyclecount.SubmitCountRequest;
import com.positivity.inventory.internal.dto.cyclecount.SubmitRecountRequest;
import com.positivity.inventory.service.CycleCountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for cycle count operations.
 *
 * <p>
 * Implements API endpoints for issue #27:
 * <ul>
 * <li>Submit counts and recounts</li>
 * <li>View count history</li>
 * <li>List assigned tasks</li>
 * </ul>
 */
@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/v1/inventory/cycleCount")
@Tag(name = "Cycle Count API", description = "API for cycle count operations and variance tracking")
public class CycleCountController {
    private final CycleCountService cycleCountService;

    /**
     * Submit a count for a cycle count task.
     */
    @PostMapping("/submit")
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_SUBMIT", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:complete"})
    @PreAuthorize("hasAuthority('inventory:cycle_count:complete')")
    @Tag(name = "Cycle Count Operations")
    @Operation(
            operationId = "submitCycleCount",
            summary = "Submit a count for a cycle count task",
            description = """
                    Records the auditor's initial physical count for an ASSIGNED cycle count task, computes the \
                    variance against the task's expected-quantity snapshot, and moves the task to \
                    COUNTED_PENDING_REVIEW — or to CONFLICT when on-hand-affecting stock movements occurred during \
                    the count window.
                    Use this tool for the first count of a task; do not use submitCycleCountRecount, which records \
                    a follow-up count, and do not use createCycleCountAdjustment, which posts the settled variance \
                    to the ledger.
                    Preconditions: the task must exist and be in ASSIGNED status; stock movements are never frozen \
                    during counts, so a CONFLICT outcome is expected behaviour, not an error.
                    Required inputs: taskId (UUID), auditorId, and actualQuantity (integer, zero or positive).
                    Emits an INVENTORY_CYCLE_COUNT_SUBMIT event; a count entry is recorded but no ledger posting or \
                    on-hand change happens here.
                    Returns 404 when the task does not exist, 409 when the task is not in ASSIGNED status, and 400 \
                    when actualQuantity is negative.
                    """,
            tags = {"Cycle Count Operations"})
    @ApiResponse(
            responseCode = "200",
            description = "Count submitted successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CountResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request or quantity")
    @ApiResponse(responseCode = "404", description = "Task not found")
    @ApiResponse(
            responseCode = "409",
            description = "Task is not in ASSIGNED status",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = com.positivity.shared.error.ApiError.class)))
    public ResponseEntity<CountResponse> submitCount(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Initial count submission identifying the task, the auditor, and the"
                                    + " quantity physically found.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Initial count", value = """
                                                                    {"taskId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "auditorId":"auditor-10042",
                                                                     "actualQuantity":12}
                                                                    """)))
                    @Valid
                    @RequestBody
                    SubmitCountRequest request) {
        log.info("Submitting cycle count request");
        CountResponse response = cycleCountService.submitCount(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Submit a recount for a cycle count task.
     */
    @PostMapping("/recount")
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_RECOUNT", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:complete"})
    @PreAuthorize("hasAuthority('inventory:cycle_count:complete')")
    @Tag(name = "Cycle Count Operations")
    @Operation(
            operationId = "submitCycleCountRecount",
            summary = "Submit a recount for a cycle count task",
            description = """
                    Records a recount for a cycle count task, appending a new count entry with a recomputed \
                    variance and re-running conflict detection against the task's count window.
                    Use this tool when a prior count is disputed or a conflict forces recounting; do not use \
                    submitCycleCount, which records the first count of a task.
                    Preconditions: the task must exist and hold fewer than 3 total counts (the original plus 2 \
                    recounts — an attempt beyond the limit flags the task REQUIRES_INVESTIGATION); permission \
                    TRIGGER_RECOUNT_SELF covers only the first recount by the original auditor, while \
                    TRIGGER_RECOUNT_ANY (manager) covers any recount.
                    Required inputs: taskId (UUID), auditorId, actualQuantity (zero or positive), and permission \
                    (TRIGGER_RECOUNT_SELF or TRIGGER_RECOUNT_ANY).
                    Emits an INVENTORY_CYCLE_COUNT_RECOUNT event; a task already in CONFLICT stays CONFLICT while \
                    in-window movements remain, because the expected-quantity snapshot is still stale.
                    Returns 404 when the task does not exist, 400 when the recount limit is already reached \
                    (RECOUNT_LIMIT_EXCEEDED) or actualQuantity is negative, and 403 when the permission context \
                    does not authorize this recount.
                    """,
            tags = {"Cycle Count Operations"})
    @ApiResponse(
            responseCode = "200",
            description = "Recount submitted successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CountResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request or recount limit exceeded")
    @ApiResponse(responseCode = "403", description = "Insufficient permission")
    @ApiResponse(responseCode = "404", description = "Task not found")
    public ResponseEntity<CountResponse> submitRecount(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Recount submission carrying the task, the auditor, the newly counted"
                                    + " quantity, and the permission context authorizing the recount.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Auditor self-recount", value = """
                                                                    {"taskId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "auditorId":"auditor-10042",
                                                                     "actualQuantity":13,
                                                                     "permission":"TRIGGER_RECOUNT_SELF"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    SubmitRecountRequest request) {
        CountResponse response = cycleCountService.submitRecount(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get a cycle count task by ID.
     */
    @GetMapping("/task/{taskId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:view"})
    @PreAuthorize("hasAuthority('inventory:cycle_count:view')")
    @Tag(name = "Cycle Count Query")
    @Operation(
            operationId = "getCycleCountTask",
            summary = "Get cycle count task details",
            description = """
                    Returns one cycle count task with its expected-quantity snapshot, assigned auditor, lifecycle \
                    status, and count-entry bookkeeping.
                    Use this tool when the taskId is already known; use listCycleCountAuditorTasks instead to \
                    discover the tasks assigned to an auditor.
                    Preconditions: the task must exist.
                    Required inputs: taskId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no cycle count task exists for the supplied id.
                    """,
            tags = {"Cycle Count Query"})
    @ApiResponse(
            responseCode = "200",
            description = "Task retrieved successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CycleCountTaskResponse.class)))
    @ApiResponse(responseCode = "404", description = "Task not found")
    public ResponseEntity<CycleCountTaskResponse> getTask(
            @Parameter(description = "Task ID") @PathVariable UUID taskId) {
        CycleCountTaskResponse task = cycleCountService.getTask(taskId);
        return ResponseEntity.ok(task);
    }

    /**
     * Get count history for a task.
     */
    @GetMapping("/task/{taskId}/history")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:view"})
    @PreAuthorize("hasAuthority('inventory:cycle_count:view')")
    @Tag(name = "Cycle Count Query")
    @Operation(
            operationId = "listCycleCountHistory",
            summary = "Get count history for a task",
            description = """
                    Returns every count entry recorded for a cycle count task — the original count and any \
                    recounts — ordered by recount sequence number.
                    Use this tool to review how counted quantities and variances evolved across recounts; use \
                    listCycleCountInterferingMovements instead for the stock movements that made the task's \
                    snapshot stale.
                    Preconditions: none are enforced; an unknown taskId yields an empty array rather than 404.
                    Required inputs: taskId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array when the task has no counts or the id is unknown, so an empty \
                    result is not an error condition.
                    """,
            tags = {"Cycle Count Query"})
    @ApiResponse(
            responseCode = "200",
            description = "History retrieved successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CountEntryResponse.class)))
    public ResponseEntity<List<CountEntryResponse>> getCountHistory(
            @Parameter(description = "Task ID") @PathVariable UUID taskId) {
        List<CountEntryResponse> history = cycleCountService.getCountHistory(taskId);
        return ResponseEntity.ok(history);
    }

    /**
     * List ledger movements interfering with a task's count window
     * (odoo-parity I2, issue #1026).
     *
     * <p>Design note (I3): the alternative of freezing stock movements for the
     * duration of a count was explicitly REJECTED — blocking movements
     * contradicts continuous shop operation. Conflict detection replaces
     * freezing: movements stay allowed, the ledger makes them listable here,
     * and a detected conflict forces an explicit reviewer choice (recount, or
     * approve with the variance recomputed against current on-hand).
     */
    @GetMapping("/task/{taskId}/interfering-movements")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:view"})
    @PreAuthorize("hasAuthority('inventory:cycle_count:view')")
    @Tag(name = "Cycle Count Query")
    @Operation(
            operationId = "listCycleCountInterferingMovements",
            summary = "List interfering movements for a cycle count task",
            description = """
                    Returns the on-hand-affecting ledger entries for the task's SKU recorded between task creation \
                    and now — the movements that make the task's expected-quantity snapshot stale and drive its \
                    CONFLICT status.
                    Use this tool when a count or adjustment reports CONFLICT and the reviewer must choose between \
                    a recount and approving with the variance recomputed; use listCycleCountHistory instead for \
                    the count entries themselves.
                    Preconditions: the task must exist; movements are never frozen during counts, so entries \
                    listed here are legitimate operations, not errors.
                    Required inputs: taskId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no cycle count task exists for the supplied id.
                    """,
            tags = {"Cycle Count Query"})
    @ApiResponse(
            responseCode = "200",
            description = "Interfering movements retrieved successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            array =
                                    @io.swagger.v3.oas.annotations.media.ArraySchema(
                                            schema = @Schema(implementation = InterferingMovementResponse.class))))
    @ApiResponse(
            responseCode = "404",
            description = "Task not found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = com.positivity.shared.error.ApiError.class)))
    public ResponseEntity<List<InterferingMovementResponse>> getInterferingMovements(
            @Parameter(description = "Task ID") @PathVariable UUID taskId) {
        return ResponseEntity.ok(cycleCountService.getInterferingMovements(taskId));
    }

    /**
     * Get tasks assigned to an auditor.
     */
    @GetMapping("/auditor/{auditorId}/tasks")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:view"})
    @PreAuthorize("hasAuthority('inventory:cycle_count:view')")
    @Tag(name = "Cycle Count Query")
    @Operation(
            operationId = "listCycleCountAuditorTasks",
            summary = "Get tasks assigned to an auditor",
            description = """
                    Returns every cycle count task assigned to one auditor, regardless of task status.
                    Use this tool to build an auditor's work queue and discover taskIds; use getCycleCountTask \
                    instead when the taskId is already known.
                    Preconditions: none; an auditor with no assignments yields an empty array.
                    Required inputs: auditorId (string) as a path parameter; there is no request body, paging or \
                    filtering.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array when the auditor has no tasks, so an empty result is not an \
                    error condition.
                    """,
            tags = {"Cycle Count Query"})
    @ApiResponse(
            responseCode = "200",
            description = "Tasks retrieved successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CycleCountTaskResponse.class)))
    public ResponseEntity<List<CycleCountTaskResponse>> getAuditorTasks(
            @Parameter(description = "Auditor ID") @PathVariable String auditorId) {
        List<CycleCountTaskResponse> tasks = cycleCountService.getTasksByAuditor(auditorId);
        return ResponseEntity.ok(tasks);
    }
}
