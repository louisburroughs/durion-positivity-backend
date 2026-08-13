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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
    @Operation(operationId = "submitNltiRequest", summary = "Submit a Natural Language Task Request", description = """
                    Submits a natural-language prompt for asynchronous task interpretation, classifying the intent \
                    as QUERY or ACTION; an ACTION prompt yields a persisted write-plan preview that must be \
                    explicitly confirmed and is never executed directly.
                    Use this tool to start or continue a natural-language task conversation; do not use \
                    confirmWritePlan, which executes a plan this operation has already previewed.
                    Preconditions: a supplied sessionId must belong to the calling subject, and a session idle for \
                    more than 24 hours is silently replaced with a new one.
                    Required inputs: prompt (non-blank text); sessionId (UUID) is optional and a new session is \
                    created when it is absent; clientContext is an optional key-value map; an optional \
                    X-Correlation-Id header is reused and echoed back, otherwise a correlation id is generated.
                    Emits a NLTI_REQUEST_SUBMIT event and persists the request with a SHA-256 prompt hash rather \
                    than the raw prompt text.
                    Returns 202 with status ACCEPTED or an attached write-plan preview, 403 when the supplied \
                    sessionId is owned by another subject, and 429 when the per-session rate limit (default 100 \
                    requests) is exceeded.
                    """)
    ResponseEntity<NltiResponseV1> submitRequest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Natural-language prompt to interpret, with optional session continuity and client context.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Workorder prompt", value = """
                                                                    {"prompt":"Create a workorder for an oil change at the downtown store",
                                                                     "sessionId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "clientContext":{"locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"}}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    NltiRequestDTO request,
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
    @Operation(operationId = "setSessionWorkflowState", summary = "Set the Session Workflow State", description = """
                    Advances the operational workflow state persisted on the caller's NLTI session so subsequent \
                    chat turns receive the workflow-gated tool set for that state.
                    Use this tool when a conversation enters or leaves an operational flow such as purchase-order \
                    creation; do not use submitNltiRequest, which submits a new prompt and never changes the \
                    workflow state directly.
                    Preconditions: the session must exist and be owned by the authenticated subject; an unknown \
                    session and a session owned by someone else are both rejected as ownership violations.
                    Required inputs: sessionId (UUID) as a path parameter and workflowState, one of IDLE, \
                    CREATING_PO, RECEIVING_ASN, INVENTORY_RECON or PROCESSING_RETURN; new sessions default to IDLE.
                    Emits a NLTI_SESSION_WORKFLOW_STATE_SET event and updates only the session's workflow state.
                    Returns 200 with the persisted state, and 403 when the session does not exist or is not owned \
                    by the caller.
                    """)
    ResponseEntity<WorkflowStateResponse> setWorkflowState(
            @PathVariable @NonNull UUID sessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The operational workflow state the session should move to.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Enter purchase-order flow",
                                                            value = "{\"workflowState\":\"CREATING_PO\"}")))
                    @Valid
                    @RequestBody
                    @NonNull
                    WorkflowStateUpdateRequest request) {
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
    @Operation(operationId = "confirmWritePlan", summary = "Confirm a Previewed Write Plan", description = """
                    Executes the previewed write plan attached to an NLTI request, invoking the plan's target tool \
                    with the exact persisted arguments rather than a re-parse of the user's text.
                    Use this tool after submitNltiRequest returned a PENDING_CONFIRMATION write-plan preview and \
                    the user approved it; do not use cancelWritePlan, which discards the plan without executing it.
                    Preconditions: the plan and its request must exist and belong to the authenticated subject; \
                    the plan must not be expired, cancelled or already executing; the caller must still hold the \
                    target tool's permission at execution time; and for MEDIUM or higher risk plans the source-entity \
                    versions captured at plan time must be unchanged.
                    Required inputs: requestId (UUID) as a path parameter; the body is optional and may carry \
                    idempotencyKey, which must match the plan's key when present.
                    Emits a NLTI_WRITE_PLAN_CONFIRM event, appends CONFIRMATION and EXECUTION audit-ledger entries, \
                    and executes the target tool; re-confirming a COMPLETE plan returns the prior outcome without \
                    re-executing.
                    Returns 200 on execution or idempotent replay, 404 when no plan or request exists for the id, \
                    403 when the plan is not owned by the caller or the tool permission is missing, 409 when the \
                    plan is cancelled, executing, previously failed, stale, or the idempotency key mismatches, 410 \
                    when the plan has expired, and 502 when the target tool call itself fails.
                    """)
    ResponseEntity<WritePlanResponseV1> confirmWritePlan(
            @PathVariable @NonNull UUID requestId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Optional confirmation payload carrying the idempotency key from the plan preview.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Confirm with idempotency key",
                                                            value =
                                                                    "{\"idempotencyKey\":\"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d\"}")))
                    @RequestBody(required = false)
                    ConfirmWritePlanRequest request,
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
    @Operation(operationId = "cancelWritePlan", summary = "Cancel a Previewed Write Plan", description = """
                    Cancels the pending write plan attached to an NLTI request so it can never be executed.
                    Use this tool when the user declines a previewed plan; do not use confirmWritePlan, which \
                    executes the plan instead of discarding it.
                    Preconditions: the plan and its request must exist and belong to the authenticated subject; a \
                    plan that is already CANCELLED or EXPIRED is returned unchanged, making the call idempotent.
                    Required inputs: requestId (UUID) as a path parameter; there is no request body.
                    Emits a NLTI_WRITE_PLAN_CANCEL event and appends a CONFIRMATION audit-ledger entry with \
                    outcome cancelled.
                    Returns 200 with the plan (idempotently for already-inactive plans), 404 when no plan or \
                    request exists for the id, 403 when the plan is not owned by the caller, and 409 when the plan \
                    is COMPLETE, EXECUTING or ERROR and can no longer be cancelled.
                    """)
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
