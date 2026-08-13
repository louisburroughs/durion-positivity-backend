package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.dto.OperationalContextOverrideRequest;
import com.positivity.workorder.internal.dto.OperationalContextResponse;
import com.positivity.workorder.internal.dto.StartWorkorderRequest;
import com.positivity.workorder.internal.dto.WorkorderStartResponse;
import com.positivity.workorder.service.WorkorderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
@Tag(name = "Operational Context", description = "Workorder execution context operations")
public class OperationalContextController {

    private final WorkorderService workorderService;

    @GetMapping("/{workorderId}/operationalContext")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getOperationalContext", summary = "Get Workorder Operational Context", description = """
                    Returns the workorder's current operational context — location, bay (derived from the assigned \
                    resource), assigned mechanics, assigned resources, and a locked flag that is true once work \
                    has started.
                    Use this tool when checking execution context before dispatch or override; do not use \
                    overrideOperationalContext, which mutates the context rather than reading it.
                    Preconditions: the workorder must exist; the context is served from the workorder's own \
                    assignment state, not from the shop-management service.
                    Required inputs: workorderId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no workorder exists for the id.
                    """)
    @ApiResponse(responseCode = "200", description = "Operational context returned")
    @ApiResponse(responseCode = "404", description = "Workorder not found")
    public ResponseEntity<OperationalContextResponse> getOperationalContext(@PathVariable UUID workorderId) {
        return ResponseEntity.ok(workorderService.getOperationalContext(workorderId));
    }

    @PostMapping("/{workorderId}/operationalContext/override")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:operationalContext:override"})
    @PreAuthorize("hasAuthority('workorder:operationalContext:override')")
    @EmitEvent(id = "WORKORDER_OPERATIONAL_CONTEXT_OVERRIDE", apiVersion = "1")
    @Operation(
            operationId = "overrideOperationalContext",
            summary = "Override Workorder Operational Context",
            description = """
                    Applies a manager-authorized override of the workorder's operational context, replacing the \
                    location, assigned mechanics, and assigned resources (the first assigned resource becomes the \
                    bay) before work starts.
                    Use this tool when a manager must re-slot a workorder to a different bay, crew, or location \
                    prior to execution; do not use getOperationalContext, which only reads the current context.
                    Preconditions: the workorder must exist and work must not have started — once workStartedAt \
                    is set the context is locked and overrides are rejected.
                    Required inputs: workorderId (UUID) as a path parameter and a body with locationId (UUID, \
                    required); bayId, assignedMechanics, assignedResources, and constraints are optional, and \
                    constraints are echoed back but not persisted.
                    Emits a WORKORDER_OPERATIONAL_CONTEXT_OVERRIDE event and marks the workorder fact changed for \
                    downstream replication.
                    Returns 404 when no workorder exists for the id, and 409 when work has already started and \
                    the context is locked.
                    """)
    @ApiResponse(responseCode = "200", description = "Override applied")
    @ApiResponse(responseCode = "404", description = "Workorder not found")
    @ApiResponse(responseCode = "409", description = "Context locked (work started)")
    public ResponseEntity<OperationalContextResponse> overrideOperationalContext(
            @PathVariable UUID workorderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Replacement operational context values — location, bay, mechanics, and resources.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Re-slot to another bay",
                                                            value = """
                                                                    {"locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a21",
                                                                     "bayId":"BAY-3",
                                                                     "assignedMechanics":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a22"],
                                                                     "assignedResources":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a23"],
                                                                     "constraints":[]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    OperationalContextOverrideRequest request) {
        return ResponseEntity.ok(workorderService.overrideOperationalContext(workorderId, request));
    }

    @PostMapping("/{workorderId}/start")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:start"})
    @PreAuthorize("hasAuthority('workorder:start')")
    @EmitEvent(id = "WORKORDER_START", apiVersion = "1")
    @Operation(
            operationId = "startWorkorder",
            summary = "Start Workorder Execution and Lock Context",
            description = """
                    Transitions the workorder to WORK_IN_PROGRESS, stamps workStartedAt, assigns a new operational \
                    context version, and locks the context against further overrides.
                    Use this tool when the technician actually begins work; do not use overrideOperationalContext, \
                    which adjusts context and is only possible before this call.
                    Preconditions: the workorder must exist in APPROVED or ASSIGNED status, work must not already \
                    have started, and no change requests may be awaiting advisor review.
                    Required inputs: workorderId (UUID) as a path parameter; the body is optional and may carry a \
                    reason (defaults to "Work started") — the acting user is taken from the security context.
                    Emits a WORKORDER_START event, captures a state snapshot, and records the status transition.
                    Returns 400 when pending change requests block the start, 404 when no workorder exists for \
                    the id, and 409 when work has already started or the status is not start-eligible.
                    """)
    @ApiResponse(responseCode = "200", description = "Work started, context locked")
    @ApiResponse(responseCode = "400", description = "Cannot start workorder due to pending change requests")
    @ApiResponse(responseCode = "404", description = "Workorder not found")
    @ApiResponse(responseCode = "409", description = "Work already started")
    public ResponseEntity<WorkorderStartResponse> startWork(
            @PathVariable UUID workorderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional start metadata; reason is recorded on the state transition.",
                            required = false,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Start with reason",
                                                            value = """
                                                                    {"reason":"Vehicle in bay, technician ready"}
                                                                    """)))
                    @RequestBody(required = false)
                    StartWorkorderRequest request) {
        try {
            String requestedUserId = SecurityContextHelper.getCurrentUsername().orElse(null);
            String reason = request != null ? request.getReason() : null;
            return ResponseEntity.ok(workorderService.startWork(workorderId, requestedUserId, reason));
        } catch (IllegalStateException ex) {
            if (isPendingChangeRequestStartFailure(ex)) {
                return ResponseEntity.badRequest()
                        .body(WorkorderStartResponse.builder()
                                .workorderId(workorderId)
                                .message(ex.getMessage())
                                .build());
            }
            throw ex;
        }
    }

    private boolean isPendingChangeRequestStartFailure(IllegalStateException ex) {
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("cannot be started")
                && normalized.contains("pending change request")
                && normalized.contains("awaiting approval");
    }
}
