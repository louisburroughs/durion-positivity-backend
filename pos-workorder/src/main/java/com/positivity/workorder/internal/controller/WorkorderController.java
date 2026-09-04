package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.dto.CountResponse;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.workorder.internal.dto.ApproveWorkorderRequest;
import com.positivity.workorder.internal.dto.CompleteWorkorderRequest;
import com.positivity.workorder.internal.dto.CompleteWorkorderResponse;
import com.positivity.workorder.internal.dto.CompletionPreconditionsResponse;
import com.positivity.workorder.internal.dto.CreateWorkorderRequest;
import com.positivity.workorder.internal.dto.ReopenWorkorderRequest;
import com.positivity.workorder.internal.dto.ReopenWorkorderResponse;
import com.positivity.workorder.internal.dto.WorkorderItemCompletionResponse;
import com.positivity.workorder.internal.dto.WorkorderResponse;
import com.positivity.workorder.internal.dto.WorkorderSnapshotResponse;
import com.positivity.workorder.internal.dto.WorkorderStateTransitionResponse;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.WorkorderCountService;
import com.positivity.workorder.internal.service.WorkorderInvoiceService;
import com.positivity.workorder.internal.service.WorkorderService;
import com.positivity.workorder.internal.service.WorkorderStateMachine;
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
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Work Order API", description = "Endpoints for work order management")
@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
@Slf4j
public class WorkorderController {
    private static final String SYSTEM_USER_ID = "system";

    private final WorkorderService workorderService;
    private final WorkorderInvoiceService workorderInvoiceService;
    private final WorkorderCountService workorderCountService;

    @Operation(operationId = "listWorkorders", summary = "List All Workorders", description = """
                    Returns every workorder in the system as an unpaginated list, regardless of status or \
                    location.
                    Use this tool only for small datasets or admin views; use searchWorkorders instead for \
                    paginated, filtered lookup, and countWorkorders when only totals are needed.
                    Preconditions: none beyond the caller holding workorder:workorder:view.
                    Required inputs: none — there are no filters or pagination parameters.
                    Emits a WORKORDER_LIST audit event; no workorder state changes — this is a read-only \
                    projection.
                    Returns 200 with the full list, possibly empty.
                    """)
    @ApiResponse(responseCode = "200", description = "List of work orders returned successfully.")
    @GetMapping
    @EmitEvent(id = "WORKORDER_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_VIEW + "')")
    public List<WorkorderResponse> getAllWorkorders() {
        return workorderService.getAllWorkorders();
    }

    @Operation(operationId = "countWorkorders", summary = "Count Workorders by Status", description = """
                    Counts workorders server-side and returns a grand total plus a per-status breakdown without \
                    listing the rows.
                    Use this tool for dashboard tiles and status widgets; use listWorkorders or searchWorkorders \
                    instead when the actual workorder rows are needed.
                    Preconditions: none beyond the caller holding workorder:workorder:view.
                    Required inputs: none — status (repeatable, exact statuses) takes precedence over openOnly, \
                    which defaults to false and, when true, counts every status except COMPLETED and CANCELLED.
                    Emits a WORKORDER_COUNT audit event; no workorder state changes — this is a read-only \
                    aggregation.
                    Returns 200 with the counts, and 400 when a status value is not a valid WorkorderStatus.
                    """)
    @ApiResponse(responseCode = "200", description = "Count returned successfully.")
    @GetMapping("/count")
    @EmitEvent(id = "WORKORDER_COUNT", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_VIEW + "')")
    public CountResponse countWorkorders(
            @Parameter(
                            description = "Count only open (non-terminal) work orders. Ignored when 'status' is "
                                    + "supplied. Defaults to false (count all statuses).",
                            schema = @Schema(type = "boolean", defaultValue = "false"))
                    @RequestParam(defaultValue = "false")
                    boolean openOnly,
            @Parameter(
                            description = "Exact statuses to count; repeatable. Takes precedence over 'openOnly'.",
                            array = @ArraySchema(schema = @Schema(implementation = WorkorderStatus.class)))
                    @RequestParam(required = false)
                    @Nullable
                    Set<WorkorderStatus> status) {
        Set<WorkorderStatus> statuses;
        if (status != null && !status.isEmpty()) {
            statuses = status;
        } else if (openOnly) {
            statuses = WorkorderStatus.getOpenStatuses();
        } else {
            statuses = null; // count every status
        }
        return workorderCountService.countByStatuses(statuses);
    }

    @Operation(operationId = "getWorkorder", summary = "Get Workorder by Id", description = """
                    Returns the raw workorder record — status, customer, vehicle, estimate linkage, approval and \
                    completion fields — for one workorder id.
                    Use this tool when the plain record is enough; use getWorkorderDetail instead for the \
                    role-aware view with capability flags, labor totals, and conditional financials.
                    Preconditions: the workorder must exist.
                    Required inputs: workorderId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no workorder exists for the id.
                    """)
    @ApiResponse(responseCode = "200", description = "Work order found and returned.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @GetMapping("/{workorderId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_VIEW + "')")
    public ResponseEntity<WorkorderResponse> getWorkorderById(
            @Parameter(
                            description = "ID of the work order to retrieve",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId) {
        return workorderService
                .getWorkorderById(workorderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(operationId = "createWorkorder", summary = "Create a New Workorder", description = """
                    Creates a DRAFT workorder from an estimate, copying the estimate's customer, location, and \
                    CRM references and generating a workorder number.
                    Use this tool when opening a workorder directly; do not use promoteEstimate, which is the \
                    estimate-side path that promotes an APPROVED estimate into a workorder.
                    Preconditions: the referenced estimate must exist, and the caller must hold \
                    workorder:workorder:create; the location falls back to the caller's primary location when \
                    the estimate carries none.
                    Required inputs: estimateId and customerId (UUIDs); an Idempotency-Key header is recommended \
                    — a repeated key returns the originally created workorder instead of a duplicate.
                    Emits a WORKORDER_CREATE event and marks the workorder fact changed for downstream \
                    replication.
                    Returns 200 with the created or replayed workorder, and 400 when the estimate cannot be \
                    found.
                    """)
    @ApiResponse(
            responseCode = "200",
            description =
                    "Work order created successfully, or existing work order returned if idempotency key was previously processed.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Estimate and customer the new workorder is created from.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = CreateWorkorderRequest.class),
                            examples = @ExampleObject(name = "createWorkorder", value = """
                                                    {"estimateId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a91",
                                                     "customerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a92"}
                                                    """)))
    @PostMapping
    @EmitEvent(id = "WORKORDER_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:create"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_CREATE + "')")
    public ResponseEntity<WorkorderResponse> createWorkorder(
            @Parameter(description = "Work order creation request") @Valid @RequestBody CreateWorkorderRequest request,
            @Parameter(
                            description =
                                    "Optional idempotency key to prevent duplicate creation (recommended for retries)",
                            example = "workorder-create-550e8400-e29b-41d4-a716-446655440000")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {
        // Service handles entity creation internally, including idempotency check
        var created = workorderService.createWorkorderWithIdempotency(
                request.getEstimateId(), request.getCustomerId(), idempotencyKey);
        return ResponseEntity.ok(created);
    }

    @Operation(operationId = "deleteWorkorder", summary = "Delete a Workorder", description = """
                    Deletes a workorder row by id, a hard delete with no status guard or soft-delete fallback.
                    Use this tool only to remove mistakenly created workorders; do not use completeWorkorder or \
                    reopenWorkorder, which drive the normal lifecycle without destroying history.
                    Preconditions: the caller must hold workorder:workorder:delete; deletion is idempotent and \
                    deleting an unknown id is a silent no-op.
                    Required inputs: workorderId (UUID) as a path parameter; there is no request body.
                    Emits a WORKORDER_DELETE event.
                    Returns 204 regardless of whether the workorder previously existed.
                    """)
    @ApiResponse(responseCode = "204", description = "Work order deleted whether or not it previously existed.")
    @DeleteMapping("/{workorderId}")
    @EmitEvent(id = "WORKORDER_DELETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:delete"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_DELETE + "')")
    public ResponseEntity<Void> deleteWorkorder(
            @Parameter(description = "ID of the work order to delete", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId) {
        workorderService.deleteWorkorder(workorderId);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "getWorkorderTransitions", summary = "Get Workorder Transition History", description = """
                    Returns the workorder's recorded state transitions — from-status, to-status, actor, reason, \
                    and timestamp for each lifecycle change.
                    Use this tool when auditing how a workorder reached its current status; use \
                    getWorkorderSnapshots instead for the captured point-in-time snapshots.
                    Preconditions: none — a workorder with no transitions yields an empty list.
                    Required inputs: workorderId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the transitions, possibly empty; no 404 is produced for unknown workorders.
                    """)
    @ApiResponse(responseCode = "200", description = "Transition history returned successfully.")
    @GetMapping("/{workorderId}/transitions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_VIEW + "')")
    public ResponseEntity<List<WorkorderStateTransitionResponse>> getTransitionHistory(
            @Parameter(description = "ID of the work order", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId) {
        return ResponseEntity.ok(workorderService.getTransitionHistory(workorderId));
    }

    @Operation(operationId = "getWorkorderSnapshots", summary = "Get Workorder Snapshot History", description = """
                    Returns the workorder's captured snapshots — immutable point-in-time records taken at \
                    lifecycle milestones such as work start, billable-scope finalization, and reopen.
                    Use this tool when reconstructing what a workorder looked like at a milestone; use \
                    getWorkorderTransitions instead for the plain status-change log.
                    Preconditions: none — a workorder with no snapshots yields an empty list.
                    Required inputs: workorderId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the snapshots, possibly empty; no 404 is produced for unknown workorders.
                    """)
    @ApiResponse(responseCode = "200", description = "Snapshot history returned successfully.")
    @GetMapping("/{workorderId}/snapshots")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_VIEW + "')")
    public ResponseEntity<List<WorkorderSnapshotResponse>> getSnapshotHistory(
            @Parameter(description = "ID of the work order", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId) {
        return ResponseEntity.ok(workorderService.getSnapshotHistory(workorderId));
    }

    @Operation(
            operationId = "approveWorkorder",
            summary = "Approve Workorder With Customer Signature",
            description = """
                    Approves a DRAFT workorder, transitioning it to APPROVED and storing the captured customer \
                    signature, signer name, and approval notes.
                    Use this tool for workorder-level customer authorization; do not use approveEstimate, which \
                    approves the estimate before a workorder exists, or approveChangeRequest, which approves \
                    mid-job additional work.
                    Preconditions: the workorder must exist, be in DRAFT status, and belong to the customerId in \
                    the request — a mismatched customer is rejected.
                    Required inputs: workorderId (UUID) as a path parameter and customerId (UUID) in the body; \
                    signatureData (base64 image), signerName, and notes are optional, and signatureMimeType \
                    defaults to image/png.
                    Emits a WORKORDER_APPROVE event and marks the workorder fact changed for downstream \
                    replication.
                    Returns 404 when the workorder does not exist, 400 when the status is not DRAFT, and 409 \
                    when the customer does not match the workorder's own customer.
                    """)
    @ApiResponse(responseCode = "200", description = "Work order approved successfully with signature captured.")
    @ApiResponse(responseCode = "400", description = "Work order cannot be approved in current state.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @ApiResponse(responseCode = "409", description = "Customer ID mismatch: workorder belongs to a different customer.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Approving customer's identity and captured signature artifacts.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = ApproveWorkorderRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "approveWorkorder",
                                            value =
                                                    "{\"customerId\":\"550e8400-e29b-41d4-a716-446655440010\",\"signatureData\":\"base64-signature\",\"signatureMimeType\":\"image/png\",\"signerName\":\"Jane Customer\",\"notes\":\"Approved by customer\"}")))
    @PostMapping("/{workorderId}/approval")
    @EmitEvent(id = "WORKORDER_APPROVE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:approve"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_APPROVE + "')")
    public ResponseEntity<WorkorderResponse> approveWorkorder(
            @Parameter(
                            description = "ID of the work order to approve",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId,
            @Parameter(description = "Approval request with customer ID and signature capture") @Valid @RequestBody
                    ApproveWorkorderRequest request) {
        try {
            var approved = workorderService.approveWorkorder(
                    workorderId,
                    request.getCustomerId(),
                    request.getSignatureData(),
                    request.getSignatureMimeType(),
                    request.getSignerName(),
                    request.getNotes());
            return ResponseEntity.ok(approved);
        } catch (IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(operationId = "completeWorkorder", summary = "Complete a Workorder", description = """
                    Completes a workorder: outstanding OPEN, READY_TO_EXECUTE, and IN_PROGRESS items are \
                    auto-completed, an immutable billable-scope snapshot is captured, the status transitions to \
                    COMPLETED, and job-time facts are published for each closed labor entry.
                    Use this tool when all work is done; use getCompletionPreconditions first to see what would \
                    block completion, and use completeServiceItem or completePartItem instead to close individual lines.
                    Preconditions: the workorder must be in WORK_IN_PROGRESS, AWAITING_PARTS, \
                    AWAITING_APPROVAL, or READY_FOR_PICKUP status; PENDING_APPROVAL items are left untouched and \
                    can block completion.
                    Required inputs: workorderId (UUID) as a path parameter; completionNotes in the body is \
                    optional and the acting user comes from the security context.
                    Emits a WORKORDER_COMPLETE event and a WorkCompleted domain event carrying the final \
                    billable scope.
                    Returns 400 with the blocking reason when the status is not completion-eligible or the \
                    workorder is already COMPLETED or CANCELLED, and 404 when the workorder does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Work order completed successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid state transition or work order already completed.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Optional completion notes recorded on the finished workorder.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = CompleteWorkorderRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "completeWorkorder",
                                            value = "{\"completionNotes\":\"Completed and verified\"}")))
    @PostMapping("/{workorderId}/complete")
    @EmitEvent(id = "WORKORDER_COMPLETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:complete"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_COMPLETE + "')")
    public ResponseEntity<CompleteWorkorderResponse> completeWorkorder(
            @Parameter(
                            description = "ID of the work order to complete",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId,
            @RequestBody CompleteWorkorderRequest request) {
        try {
            String previousStatus = workorderService.getCurrentWorkorderStatus(workorderId);

            // Complete the work order (this will also emit the event)
            workorderService.completeWorkorder(workorderId, resolveCurrentActorUserId(), request.getCompletionNotes());

            CompleteWorkorderResponse response = CompleteWorkorderResponse.builder()
                    .workorderId(workorderId)
                    .previousStatus(previousStatus)
                    .currentStatus("COMPLETED")
                    .completedAt(workorderService.getCompletedAt(workorderId))
                    .message("Work order completed successfully")
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(CompleteWorkorderResponse.builder()
                            .workorderId(workorderId)
                            .message(e.getMessage())
                            .build());
        }
    }

    @Operation(
            operationId = "generateWorkorderInvoice",
            summary = "Request Invoice Generation From Workorder",
            description = """
                    Queues asynchronous invoice generation for a completed workorder over the Kafka command \
                    feed; the invoiceId appears on the workorder later, once the invoicing domain's fact links \
                    it back.
                    Use this tool after completeWorkorder succeeds; do not call it to fetch an invoice — poll \
                    getWorkorder for the linked invoiceId instead.
                    Preconditions: the workorder must exist and be in COMPLETED status, and the Kafka event feed \
                    must be enabled; an already-invoiced workorder short-circuits to its linked invoice.
                    Required inputs: workorderId (UUID) as a path parameter; an Idempotency-Key header collapses \
                    retries into one generation, defaulting to the workorderId itself.
                    Emits a WORKORDER_INVOICE_GENERATE event and publishes an invoice-creation command; callers \
                    must treat a PENDING status as queued rather than generated.
                    Returns 202 with status PENDING when generation is queued, 200 with the linked invoice on \
                    idempotent replay, 404 when the workorder does not exist, 409 when it is not COMPLETED, and \
                    503 when the Kafka feed is disabled or the broker rejects the command.
                    """)
    @ApiResponse(
            responseCode = "202",
            description = "Generation queued; poll the workorder for the linked invoiceId (status PENDING).")
    @ApiResponse(responseCode = "200", description = "Existing linked invoice returned for idempotent replay.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @ApiResponse(responseCode = "409", description = "Work order is not in COMPLETED state.")
    @PostMapping("/{workorderId}/generate-invoice")
    @EmitEvent(id = "WORKORDER_INVOICE_GENERATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:generate_invoice"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_GENERATE_INVOICE + "')")
    public ResponseEntity<InvoiceGenerationResponse> generateInvoice(
            @Parameter(description = "ID of the completed work order", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId,
            @Parameter(
                            description =
                                    "Optional idempotency key to prevent duplicate invoice generation (recommended for retries)",
                            example = "invoice-generate-550e8400-e29b-41d4-a716-446655440000")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {

        InvoiceGenerationResponse response = workorderInvoiceService.generateInvoice(workorderId, idempotencyKey);
        return WorkorderInvoiceService.STATUS_PENDING.equals(response.getStatus())
                ? ResponseEntity.accepted().body(response)
                : ResponseEntity.ok(response);
    }

    @Operation(
            operationId = "getCompletionPreconditions",
            summary = "Evaluate Workorder Completion Preconditions",
            description = """
                    Evaluates whether a workorder can be completed, returning a canComplete flag, a checklist, \
                    blocking reasons, counts of unresolved approval-gated change requests and non-terminal \
                    items, the emergency-denial acknowledgment state, and whether billable items exist.
                    Use this tool before completeWorkorder to surface blockers without attempting the \
                    transition; do not use checkWorkorderCanClose, which checks only the emergency-denial \
                    acknowledgment dimension.
                    Preconditions: the workorder must exist.
                    Required inputs: workorderId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only evaluation.
                    Returns 404 when no workorder exists for the id.
                    """)
    @ApiResponse(responseCode = "200", description = "Completion preconditions evaluated successfully.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @GetMapping("/{workorderId}/completion-preconditions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:complete"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_COMPLETE + "')")
    public ResponseEntity<CompletionPreconditionsResponse> getCompletionPreconditions(
            @Parameter(
                            description = "ID of the workorder to validate",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId) {
        WorkorderStateMachine.CompletionPreconditions preconditions =
                workorderService.getCompletionPreconditions(workorderId);

        CompletionPreconditionsResponse response = CompletionPreconditionsResponse.builder()
                .workorderId(preconditions.workorderId())
                .canComplete(preconditions.canComplete())
                .currentStatus(preconditions.currentStatus())
                .checklistItems(preconditions.checklistItems())
                .blockingReasons(preconditions.blockingReasons())
                .unresolvedApprovalGatedChangeRequests(preconditions.unresolvedApprovalGatedChangeRequests())
                .nonTerminalServiceItems(preconditions.nonTerminalServiceItems())
                .nonTerminalPartItems(preconditions.nonTerminalPartItems())
                .emergencyDenialAcknowledged(preconditions.emergencyDenialAcknowledged())
                .hasBillableItems(preconditions.hasBillableItems())
                .build();

        return ResponseEntity.ok(response);
    }

    @Operation(operationId = "reopenWorkorder", summary = "Reopen a Completed Workorder", description = """
                    Reopens a COMPLETED workorder in a controlled way: the finalized billable scope is \
                    superseded via a new snapshot, and the workorder is flagged isReopened with the actor, \
                    reason, and timestamp recorded — the status itself remains COMPLETED.
                    Use this tool when post-completion corrections are needed; do not use deleteWorkorder, which \
                    destroys the record instead of auditing a reopen.
                    Preconditions: the workorder must exist and be in COMPLETED status, and the caller must hold \
                    workorder:workorder:reopen_completed.
                    Required inputs: workorderId (UUID) as a path parameter and a non-blank reopenReason in the \
                    body; the acting user comes from the security context.
                    Emits a WORKORDER_REOPEN event and captures a BILLABLE_SCOPE_SUPERSEDED snapshot.
                    Returns 400 with the reason when the workorder is not COMPLETED or the reason is missing, \
                    and 404 when the workorder does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Workorder reopened successfully.")
    @ApiResponse(responseCode = "400", description = "Workorder cannot be reopened or reason missing.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Mandatory reason justifying the reopen of the completed workorder.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = ReopenWorkorderRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "reopenWorkorder",
                                            value = "{\"reopenReason\":\"Customer requested additional work\"}")))
    @PostMapping("/{workorderId}/reopen")
    @EmitEvent(id = "WORKORDER_REOPEN", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:reopen_completed"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_REOPEN_COMPLETED + "')")
    public ResponseEntity<ReopenWorkorderResponse> reopenWorkorder(
            @Parameter(
                            description = "ID of the completed workorder to reopen",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId,
            @RequestBody ReopenWorkorderRequest request) {
        try {
            WorkorderService.ReopenResult reopened = workorderService.reopenCompletedWorkorder(
                    workorderId,
                    SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER_ID),
                    request.getReopenReason());

            ReopenWorkorderResponse response = ReopenWorkorderResponse.builder()
                    .workorderId(reopened.workorderId())
                    .currentStatus(reopened.currentStatus())
                    .isReopened(reopened.isReopened())
                    .reopenedAt(reopened.reopenedAt())
                    .message("Workorder reopened successfully")
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(ReopenWorkorderResponse.builder()
                            .workorderId(workorderId)
                            .message(e.getMessage())
                            .build());
        }
    }

    @Operation(operationId = "completeServiceItem", summary = "Complete a Workorder Service Line", description = """
                    Marks one workorder service line as COMPLETED, leaving the workorder's own status untouched.
                    Use this tool to close individual service lines as work finishes; do not use \
                    completeWorkorder, which completes the whole workorder, or completePartItem, which closes a \
                    part line.
                    Preconditions: the service line must exist, belong to the given workorder, and not be in \
                    CANCELLED or PENDING_APPROVAL status; completing an already-COMPLETED line is an idempotent \
                    no-op.
                    Required inputs: workorderId and serviceLineId (UUIDs) as path parameters; there is no \
                    request body.
                    Emits a WORKORDER_SERVICE_ITEM_COMPLETE event.
                    Returns 404 when the line is missing or belongs to another workorder, and 400 when the line \
                    is CANCELLED or PENDING_APPROVAL.
                    """)
    @ApiResponse(responseCode = "200", description = "Service line completed.")
    @ApiResponse(responseCode = "400", description = "Item not completable in its current status.")
    @ApiResponse(responseCode = "404", description = "Workorder or service line not found.")
    @PostMapping("/{workorderId}/services/{serviceLineId}/complete")
    @EmitEvent(id = "WORKORDER_SERVICE_ITEM_COMPLETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:complete"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_COMPLETE + "')")
    public ResponseEntity<WorkorderItemCompletionResponse> completeServiceItem(
            @Parameter(description = "Workorder ID") @PathVariable UUID workorderId,
            @Parameter(description = "Service line ID") @PathVariable UUID serviceLineId) {
        try {
            return ResponseEntity.ok(
                    workorderService.completeServiceItem(workorderId, serviceLineId, resolveCurrentActorUserId()));
        } catch (IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(operationId = "completePartItem", summary = "Complete a Workorder Part Line", description = """
                    Marks one workorder part as COMPLETED and flags the workorder fact changed for downstream \
                    replication, leaving the workorder's own status untouched.
                    Use this tool to close individual part lines as they are installed; do not use \
                    completeWorkorder, which completes the whole workorder, or completeServiceItem, which closes \
                    a service line.
                    Preconditions: the part must exist, belong to the given workorder directly or via its \
                    service line, and not be in CANCELLED or PENDING_APPROVAL status; completing an \
                    already-COMPLETED part is an idempotent no-op.
                    Required inputs: workorderId and partId (UUIDs) as path parameters; there is no request body.
                    Emits a WORKORDER_PART_ITEM_COMPLETE event.
                    Returns 404 when the part is missing or belongs to another workorder, and 400 when the part \
                    is CANCELLED or PENDING_APPROVAL.
                    """)
    @ApiResponse(responseCode = "200", description = "Part completed.")
    @ApiResponse(responseCode = "400", description = "Item not completable in its current status.")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found.")
    @PostMapping("/{workorderId}/parts/{partId}/complete")
    @EmitEvent(id = "WORKORDER_PART_ITEM_COMPLETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:complete"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_COMPLETE + "')")
    public ResponseEntity<WorkorderItemCompletionResponse> completePartItem(
            @Parameter(description = "Workorder ID") @PathVariable UUID workorderId,
            @Parameter(description = "Part ID") @PathVariable UUID partId) {
        try {
            return ResponseEntity.ok(
                    workorderService.completePartItem(workorderId, partId, resolveCurrentActorUserId()));
        } catch (IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    private String resolveCurrentActorUserId() {
        return SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER_ID);
    }
}
