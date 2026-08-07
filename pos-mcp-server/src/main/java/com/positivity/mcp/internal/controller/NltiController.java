package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.dto.WritePlanResponseV1;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.NltiWorkflowStateService;
import com.positivity.mcp.internal.service.NltiWritePlanService;
import com.positivity.mcp.internal.service.PermissionCodes;
import com.positivity.mcp.service.NltiRequestService;
import com.positivity.security.common.SecurityContextHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"nlti:request:submit"})
@RequestMapping("/v1/nlt")
@Tag(name = "NLTI", description = "Natural Language Task Interface")
public class NltiController {

    private final NltiRequestService nltiRequestService;
    private final NltiWorkflowStateService workflowStateService;
    private final NltiWritePlanService writePlanService;

    NltiController(
            @NonNull NltiRequestService nltiRequestService,
            @NonNull NltiWorkflowStateService workflowStateService,
            @NonNull NltiWritePlanService writePlanService) {
        this.nltiRequestService = nltiRequestService;
        this.workflowStateService = workflowStateService;
        this.writePlanService = writePlanService;
    }

    @PostMapping("/requests")
    @EmitEvent(id = "NLTI_REQUEST_SUBMIT", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + McpPermissions.NLTI_REQUEST_SUBMIT + "')")
    @Operation(
            summary = "Submit a natural language task request",
            description =
                    "Submit a natural language task request for asynchronous MCP processing and correlation tracking")
    ResponseEntity<NltiResponseV1> submitRequest(
            @Valid @RequestBody @NonNull NltiRequestDTO request,
            @RequestHeader(value = NltiCorrelationIdSupport.CORRELATION_ID_HEADER, required = false)
                    String correlationIdHeader,
            @NonNull HttpServletRequest servletRequest) {
        UUID resolvedCorrelationId = NltiCorrelationIdSupport.resolveFromHeader(correlationIdHeader);
        servletRequest.setAttribute(NltiCorrelationIdSupport.CORRELATION_ID_ATTRIBUTE, resolvedCorrelationId);
        NltiResponseV1 response = nltiRequestService.submit(request, resolvedCorrelationId);
        return ResponseEntity.accepted()
                .header(
                        NltiCorrelationIdSupport.CORRELATION_ID_HEADER,
                        response.correlationId().toString())
                .body(response);
    }

    /**
     * #778: explicit write path that advances a session's operational {@link WorkflowState} as a
     * workflow progresses (e.g. entering a purchase-order flow), so subsequent chat turns on that
     * session receive the workflow-gated tool set. Ownership-checked: the caller may only advance
     * their own session.
     */
    @PostMapping("/sessions/{sessionId}/workflow-state")
    @EmitEvent(id = "NLTI_SESSION_WORKFLOW_STATE_SET", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + McpPermissions.NLTI_REQUEST_SUBMIT + "')")
    @Operation(
            summary = "Set the session workflow state",
            description =
                    "Advances the operational workflow state of the caller's NLTI session so subsequent chat turns receive the workflow-gated tool set.")
    ResponseEntity<WorkflowStateResponse> setWorkflowState(
            @PathVariable @NonNull UUID sessionId, @Valid @RequestBody @NonNull WorkflowStateUpdateRequest request) {
        // Fail fast on a missing authenticated principal rather than defaulting to a sentinel subject:
        // subjectId is the ownership key for this session-mutating call, so an unresolved username must
        // never silently pass through (or mis-attribute the ownership check).
        String subjectId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException(
                        "No authenticated username available for workflow-state update"));
        WorkflowState updatedState = workflowStateService.advance(sessionId, subjectId, request.workflowState());
        return ResponseEntity.ok(new WorkflowStateResponse(sessionId, updatedState));
    }

    /**
     * Gate 6 (#1193): explicit confirmation of a previewed write plan. Executes the plan's exact
     * persisted arguments (never a re-parse of the user's text) after re-checking permission,
     * expiry, idempotency, and stale-data invariants. Ownership-checked: the caller may only
     * confirm plans on their own session.
     */
    @PostMapping("/requests/{requestId}/confirm")
    @EmitEvent(id = "NLTI_WRITE_PLAN_CONFIRM", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + McpPermissions.NLTI_REQUEST_SUBMIT + "')")
    @Operation(
            summary = "Confirm a previewed write plan",
            description = "Executes the exact persisted arguments of the write plan attached to the request. "
                    + "Rejects expired plans, re-checks the caller's permission at execution time, and returns "
                    + "the prior outcome for an already-executed idempotency key instead of re-executing.")
    ResponseEntity<WritePlanResponseV1> confirmWritePlan(
            @PathVariable @NonNull UUID requestId,
            @RequestBody(required = false) ConfirmWritePlanRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        String subjectId = requireSubjectId("write-plan confirmation");
        return ResponseEntity.ok(writePlanService.confirm(
                requestId,
                subjectId,
                PermissionCodes.extract(SecurityContextHelper.getAuthorities()),
                request == null ? null : request.idempotencyKey(),
                authorizationHeader));
    }

    /** Gate 6 (#1193): cancels a previewed write plan (idempotent for already-inactive plans). */
    @PostMapping("/requests/{requestId}/cancel")
    @EmitEvent(id = "NLTI_WRITE_PLAN_CANCEL", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + McpPermissions.NLTI_REQUEST_SUBMIT + "')")
    @Operation(
            summary = "Cancel a previewed write plan",
            description = "Cancels the pending write plan attached to the request without executing it.")
    ResponseEntity<WritePlanResponseV1> cancelWritePlan(@PathVariable @NonNull UUID requestId) {
        String subjectId = requireSubjectId("write-plan cancellation");
        return ResponseEntity.ok(writePlanService.cancel(requestId, subjectId));
    }

    private static @NonNull String requireSubjectId(@NonNull String operation) {
        // Fail fast on a missing authenticated principal: subjectId is the session-ownership key.
        return SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException(
                        "No authenticated username available for " + operation));
    }

    @Schema(name = "ConfirmWritePlanRequest", description = "Optional confirmation payload")
    public record ConfirmWritePlanRequest(
            @Schema(
                    description = "Idempotency key from the plan preview; when present it must match the plan",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String idempotencyKey) {}

    @Schema(name = "WorkflowStateUpdateRequest", description = "Requested workflow state for the session")
    public record WorkflowStateUpdateRequest(
            @Schema(
                    description = "The operational workflow state to set on the session",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull
            WorkflowState workflowState) {}

    @Schema(name = "WorkflowStateResponse", description = "The session's workflow state after the update")
    public record WorkflowStateResponse(
            @Schema(description = "Session identifier", requiredMode = Schema.RequiredMode.REQUIRED) @NonNull
            UUID sessionId,

            @Schema(description = "Current workflow state", requiredMode = Schema.RequiredMode.REQUIRED) @NonNull
            WorkflowState workflowState) {}
}
