package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.NltiWorkflowStateService;
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

    NltiController(
            @NonNull NltiRequestService nltiRequestService, @NonNull NltiWorkflowStateService workflowStateService) {
        this.nltiRequestService = nltiRequestService;
        this.workflowStateService = workflowStateService;
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
