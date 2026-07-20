package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
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
import com.positivity.workorder.internal.service.WorkorderStateMachine;
import com.positivity.workorder.internal.observability.BusinessSpanSupport;
import com.positivity.workorder.service.WorkorderInvoiceService;
import com.positivity.workorder.service.WorkorderService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
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
import org.springframework.web.bind.annotation.*;

@Tag(name = "Work Order API", description = "Endpoints for work order management")
@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
@Slf4j
public class WorkorderController {
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-workorder");
    private static final String DOMAIN = "work-order-execution";
    private static final String TEAM = "workorder-eng";
    private static final String SYSTEM_USER_ID = "system";

    private final WorkorderService workorderService;
    private final WorkorderInvoiceService workorderInvoiceService;

    @Operation(summary = "Get all work orders", description = "Retrieve a list of all work orders.")
    @ApiResponse(responseCode = "200", description = "List of work orders returned successfully.")
    @GetMapping
    @EmitEvent(id = "WORKORDER_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:view"})
    @PreAuthorize("hasAuthority('workorder:workorder:view')")
    public List<WorkorderResponse> getAllWorkorders() {
        return workorderService.getAllWorkorders().stream()
                .map(WorkorderResponse::fromEntity)
                .toList();
    }

    @Operation(summary = "Get work order by ID", description = "Retrieve a work order by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Work order found and returned.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @GetMapping("/{workorderId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:view"})
    @PreAuthorize("hasAuthority('workorder:workorder:view')")
    public ResponseEntity<WorkorderResponse> getWorkorderById(
            @Parameter(
                            description = "ID of the work order to retrieve",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId) {
        return workorderService
                .getWorkorderById(workorderId)
                .map(WorkorderResponse::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Create a new work order",
            description =
                    "Add a new work order to the system. Supports idempotent creation via Idempotency-Key header to prevent duplicate workorders.")
    @ApiResponse(
            responseCode = "200",
            description =
                    "Work order created successfully, or existing work order returned if idempotency key was previously processed.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Work order creation request",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = CreateWorkorderRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "createWorkorder",
                                            value =
                                                    "{\"estimateId\":\"550e8400-e29b-41d4-a716-446655440001\",\"customerId\":\"550e8400-e29b-41d4-a716-446655440010\"}")))
    @PostMapping
    @EmitEvent(id = "WORKORDER_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
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
        return ResponseEntity.ok(WorkorderResponse.fromEntity(created));
    }

    @Operation(summary = "Delete a work order", description = "Delete a work order by its unique ID.")
    @ApiResponse(responseCode = "204", description = "Work order deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @DeleteMapping("/{workorderId}")
    @EmitEvent(id = "WORKORDER_DELETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteWorkorder(
            @Parameter(description = "ID of the work order to delete", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId) {
        workorderService.deleteWorkorder(workorderId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Get transition history",
            description = "Retrieve the state transition history for a work order.")
    @ApiResponse(responseCode = "200", description = "Transition history returned successfully.")
    @GetMapping("/{workorderId}/transitions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<WorkorderStateTransitionResponse>> getTransitionHistory(
            @Parameter(description = "ID of the work order", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId) {
        var history = workorderService.getTransitionHistory(workorderId);
        return ResponseEntity.ok(history.stream()
                .map(WorkorderStateTransitionResponse::fromEntity)
                .toList());
    }

    @Operation(summary = "Get snapshot history", description = "Retrieve the snapshot history for a work order.")
    @ApiResponse(responseCode = "200", description = "Snapshot history returned successfully.")
    @GetMapping("/{workorderId}/snapshots")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<WorkorderSnapshotResponse>> getSnapshotHistory(
            @Parameter(description = "ID of the work order", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId) {
        var history = workorderService.getSnapshotHistory(workorderId);
        return ResponseEntity.ok(
                history.stream().map(WorkorderSnapshotResponse::fromEntity).toList());
    }

    @Operation(
            summary = "Approve a work order with customer signature",
            description = "Transition work order to APPROVED status with customer signature capture. "
                    + "Work order can be approved from DRAFT status. Requires customer ID validation "
                    + "and signature data (base64-encoded image).")
    @ApiResponse(responseCode = "200", description = "Work order approved successfully with signature captured.")
    @ApiResponse(
            responseCode = "400",
            description = "Work order cannot be approved in current state or customer ID mismatch.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Approval request with customer ID and signature capture",
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
    @PreAuthorize("hasAuthority('workorder:workorder:approve')")
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
            return ResponseEntity.ok(WorkorderResponse.fromEntity(approved));
        } catch (IllegalStateException | IllegalArgumentException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(
            summary = "Complete a work order",
            description =
                    "Complete a work order, transitioning it to COMPLETED status and emitting a WorkCompleted event.")
    @ApiResponse(responseCode = "200", description = "Work order completed successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid state transition or work order already completed.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Complete workorder request",
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
    @PreAuthorize("hasAuthority('workorder:workorder:complete')")
    public ResponseEntity<CompleteWorkorderResponse> completeWorkorder(
            @Parameter(
                            description = "ID of the work order to complete",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId,
            @RequestBody CompleteWorkorderRequest request) {
        Span span = TRACER.spanBuilder("Complete Workorder").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Complete Workorder");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
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

            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_FAILURE);
            return ResponseEntity.badRequest()
                    .body(CompleteWorkorderResponse.builder()
                            .workorderId(workorderId)
                            .message(e.getMessage())
                            .build());
        } catch (IllegalArgumentException _) {
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_FAILURE);
            return ResponseEntity.notFound().build();
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Operation(
            summary = "Request invoice generation from a completed workorder",
            description = "Queues asynchronous invoice generation (ADR-0044 #900): the response is 202 with "
                    + "status PENDING and the invoiceId appears on the workorder once the invoice.events.v1 "
                    + "fact links it. Re-sending the same Idempotency-Key collapses to one generation. When "
                    + "the workorder is already invoiced, the linked invoice is returned with 200.")
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
    @PreAuthorize("hasAuthority('workorder:workorder:generate_invoice')")
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

        Span span = TRACER.spanBuilder("Generate Workorder Invoice").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Generate Workorder Invoice");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            InvoiceGenerationResponse response = workorderInvoiceService.generateInvoice(workorderId, idempotencyKey);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return WorkorderInvoiceService.STATUS_PENDING.equals(response.getStatus())
                    ? ResponseEntity.accepted().body(response)
                    : ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Operation(
            summary = "Validate completion preconditions",
            description = "Evaluate completion preconditions for a workorder and return checklist + blocking reasons.")
    @ApiResponse(responseCode = "200", description = "Completion preconditions evaluated successfully.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @GetMapping("/{workorderId}/completion-preconditions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:complete"})
    @PreAuthorize("hasAuthority('workorder:workorder:complete')")
    public ResponseEntity<CompletionPreconditionsResponse> getCompletionPreconditions(
            @Parameter(
                            description = "ID of the workorder to validate",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId) {
        try {
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
        } catch (IllegalArgumentException _) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(
            summary = "Reopen completed workorder",
            description =
                    "Controlled reopen for completed workorders. Requires elevated permission and mandatory reason.")
    @ApiResponse(responseCode = "200", description = "Workorder reopened successfully.")
    @ApiResponse(responseCode = "400", description = "Workorder cannot be reopened or reason missing.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Reopen workorder request",
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
    @PreAuthorize("hasAuthority('workorder:workorder:reopen_completed')")
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
        } catch (IllegalArgumentException _) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(ReopenWorkorderResponse.builder()
                            .workorderId(workorderId)
                            .message(e.getMessage())
                            .build());
        }
    }

    @Operation(
            summary = "Complete a workorder service line",
            description = "Mark a single service line as COMPLETED. Allowed from OPEN/READY_TO_EXECUTE/IN_PROGRESS; "
                    + "rejected for CANCELLED or PENDING_APPROVAL items.")
    @ApiResponse(responseCode = "200", description = "Service line completed.")
    @ApiResponse(responseCode = "400", description = "Item not completable in its current status.")
    @ApiResponse(responseCode = "404", description = "Workorder or service line not found.")
    @PostMapping("/{workorderId}/services/{serviceLineId}/complete")
    @EmitEvent(id = "WORKORDER_SERVICE_ITEM_COMPLETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:complete"})
    @PreAuthorize("hasAuthority('workorder:workorder:complete')")
    public ResponseEntity<WorkorderItemCompletionResponse> completeServiceItem(
            @Parameter(description = "Workorder ID") @PathVariable UUID workorderId,
            @Parameter(description = "Service line ID") @PathVariable UUID serviceLineId) {
        try {
            return ResponseEntity.ok(
                    workorderService.completeServiceItem(workorderId, serviceLineId, resolveCurrentActorUserId()));
        } catch (IllegalArgumentException _) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(
            summary = "Complete a workorder part",
            description = "Mark a single part as COMPLETED. Allowed from OPEN/READY_TO_EXECUTE/IN_PROGRESS; "
                    + "rejected for CANCELLED or PENDING_APPROVAL items.")
    @ApiResponse(responseCode = "200", description = "Part completed.")
    @ApiResponse(responseCode = "400", description = "Item not completable in its current status.")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found.")
    @PostMapping("/{workorderId}/parts/{partId}/complete")
    @EmitEvent(id = "WORKORDER_PART_ITEM_COMPLETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:complete"})
    @PreAuthorize("hasAuthority('workorder:workorder:complete')")
    public ResponseEntity<WorkorderItemCompletionResponse> completePartItem(
            @Parameter(description = "Workorder ID") @PathVariable UUID workorderId,
            @Parameter(description = "Part ID") @PathVariable UUID partId) {
        try {
            return ResponseEntity.ok(
                    workorderService.completePartItem(workorderId, partId, resolveCurrentActorUserId()));
        } catch (IllegalArgumentException _) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    private String resolveCurrentActorUserId() {
        return SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER_ID);
    }
}
