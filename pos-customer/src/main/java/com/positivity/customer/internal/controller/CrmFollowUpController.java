package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.CloseFollowUpTaskRequest;
import com.positivity.customer.internal.dto.CreateFollowUpTaskRequest;
import com.positivity.customer.internal.dto.FollowUpTaskResponse;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.FollowUpTaskService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer follow-up tasks and the CSR work queue (Story #1153).
 *
 * <p>Automatic creation from declined-service and service-due facts arrives with FI-3 (#1133);
 * everything here is usable without it.
 */
@Tag(name = "CRM Follow-ups", description = "Customer follow-up tasks and the CSR work queue")
@RestController
@RequestMapping("/v1/crm")
public class CrmFollowUpController {

    private final FollowUpTaskService followUpTaskService;

    public CrmFollowUpController(FollowUpTaskService followUpTaskService) {
        this.followUpTaskService = followUpTaskService;
    }

    @Operation(operationId = "getFollowUpQueue", summary = "Get Follow-Up Work Queue", description = """
                    Returns the CSR follow-up work queue across all parties, ordered by due date, optionally \
                    filtered by status, assignee, and task type.
                    Use this tool when working the shared queue of outstanding follow-ups; use \
                    listPartyFollowUps instead to see tasks raised against one specific party.
                    Preconditions: none; an empty page is returned when no task matches the filters.
                    Required inputs: none; status filters on OPEN, DONE, or DISMISSED, type filters on \
                    DECLINED_SERVICE_FOLLOWUP, SERVICE_DUE_REMINDER, FLEET_CHECKIN, CAMPAIGN_RESPONSE, or \
                    GENERAL, page defaults to 0, and size defaults to 50 clamped between 1 and 200.
                    Emits a CRM_FOLLOWUP_QUEUE audit event; no state changes occur.
                    Returns 200 with an empty page rather than an error when the queue is clear.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Queue returned",
                content = @Content(schema = @Schema(implementation = PagedResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/follow-ups")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.FOLLOWUP_VIEW})
    @PreAuthorize("hasAuthority('crm:followup:view')")
    @EmitEvent(id = "CRM_FOLLOWUP_QUEUE", apiVersion = "1")
    public ResponseEntity<PagedResponse<FollowUpTaskResponse>> queue(
            @RequestParam(name = "status", required = false) FollowUpStatus status,
            @RequestParam(name = "assignedTo", required = false) String assignedTo,
            @RequestParam(name = "type", required = false) FollowUpType type,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(followUpTaskService.queue(status, assignedTo, type, page, size));
    }

    @Operation(operationId = "getFollowUpTask", summary = "Get Follow-Up Task", description = """
                    Returns one follow-up task with its party, vehicle, type, due date, assignee, status, \
                    and any recorded outcome and booking references.
                    Use this tool when the task id is already known; use getFollowUpQueue instead to \
                    discover tasks by status, assignee, or type.
                    Preconditions: the follow-up task must exist.
                    Required inputs: taskId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_FOLLOWUP_GET audit event; no state changes occur.
                    Returns 404 when no follow-up task exists for the supplied taskId.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Task returned",
                content = @Content(schema = @Schema(implementation = FollowUpTaskResponse.class))),
        @ApiResponse(responseCode = "404", description = "Task not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/follow-ups/{taskId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.FOLLOWUP_VIEW})
    @PreAuthorize("hasAuthority('crm:followup:view')")
    @EmitEvent(id = "CRM_FOLLOWUP_GET", apiVersion = "1")
    public ResponseEntity<FollowUpTaskResponse> get(@PathVariable UUID taskId) {
        return ResponseEntity.ok(followUpTaskService.get(taskId));
    }

    @Operation(operationId = "listPartyFollowUps", summary = "List Party Follow-Up Tasks", description = """
                    Returns the follow-up tasks raised against one party, ordered by due date then creation \
                    time, optionally filtered by status.
                    Use this tool when reviewing outstanding work for a specific customer; use \
                    getFollowUpQueue instead for the cross-party CSR work queue.
                    Preconditions: none; an unknown partyId yields an empty page rather than an error.
                    Required inputs: partyId (UUID) as a path parameter; status optionally filters on OPEN, \
                    DONE, or DISMISSED, page defaults to 0, and size defaults to 50 clamped between 1 and \
                    200.
                    Emits a CRM_FOLLOWUP_LIST audit event; no state changes occur.
                    Returns 200 with an empty page rather than an error when the party has no follow-ups.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Tasks returned",
                content = @Content(schema = @Schema(implementation = PagedResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/parties/{partyId}/follow-ups")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.FOLLOWUP_VIEW})
    @PreAuthorize("hasAuthority('crm:followup:view')")
    @EmitEvent(id = "CRM_FOLLOWUP_LIST", apiVersion = "1")
    public ResponseEntity<PagedResponse<FollowUpTaskResponse>> listForParty(
            @PathVariable UUID partyId,
            @RequestParam(name = "status", required = false) FollowUpStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(followUpTaskService.listForParty(partyId, status, page, size));
    }

    @Operation(operationId = "createFollowUpTask", summary = "Raise Follow-Up Task", description = """
                    Raises a new OPEN follow-up task against a party, recording the creating user from the \
                    security context.
                    Use this tool when a customer needs a future touch such as a declined-service or \
                    service-due follow-up; do not use completeFollowUpTask or dismissFollowUpTask, which \
                    close an existing task.
                    Preconditions: the party must exist as either a commercial or person party.
                    Required inputs: partyId (UUID) as a path parameter and type in the body \
                    (DECLINED_SERVICE_FOLLOWUP, SERVICE_DUE_REMINDER, FLEET_CHECKIN, CAMPAIGN_RESPONSE, or \
                    GENERAL); vehicleId, dueDate, assignedTo, reason, sourceWorkorderId, and notes are \
                    optional, and an omitted assignedTo leaves the task in the shared queue.
                    Emits a CRM_FOLLOWUP_CREATE event and persists the task with status OPEN.
                    Returns 404 when no party exists for the supplied partyId, and 400 when type is missing.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Task raised",
                content = @Content(schema = @Schema(implementation = FollowUpTaskResponse.class))),
        @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/parties/{partyId}/follow-ups")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.FOLLOWUP_MANAGE})
    @PreAuthorize("hasAuthority('crm:followup:manage')")
    @EmitEvent(id = "CRM_FOLLOWUP_CREATE", apiVersion = "1")
    public ResponseEntity<FollowUpTaskResponse> create(
            @PathVariable UUID partyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The follow-up work to schedule against the party, typed by follow-up kind.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Declined-service follow-up", value = """
                                                                    {"type":"DECLINED_SERVICE_FOLLOWUP",
                                                                     "vehicleId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e",
                                                                     "dueDate":"2026-09-01",
                                                                     "reason":"Declined rear brake pads at last visit",
                                                                     "notes":"Quote given: $420 parts and labor"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateFollowUpTaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(followUpTaskService.create(partyId, request));
    }

    @Operation(operationId = "assignFollowUpTask", summary = "Assign Follow-Up Task", description = """
                    Assigns an open follow-up task to a CSR, or returns it to the shared queue when \
                    assignedTo is omitted.
                    Use this tool when routing queue work to a specific person; do not use \
                    completeFollowUpTask or dismissFollowUpTask, which close the task rather than route it.
                    Preconditions: the task must exist and still be OPEN; closed tasks cannot be modified \
                    and a new follow-up must be raised instead.
                    Required inputs: taskId (UUID) as a path parameter; assignedTo is an optional query \
                    parameter whose omission unassigns the task, and there is no request body.
                    Emits a CRM_FOLLOWUP_ASSIGN event; only the task's assignee changes.
                    Returns 404 when the task does not exist, and 422 when the task is already DONE or \
                    DISMISSED.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Task assigned",
                content = @Content(schema = @Schema(implementation = FollowUpTaskResponse.class))),
        @ApiResponse(responseCode = "404", description = "Task not found", content = @Content),
        @ApiResponse(responseCode = "422", description = "Task is already closed", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PutMapping("/follow-ups/{taskId}/assignee")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.FOLLOWUP_MANAGE})
    @PreAuthorize("hasAuthority('crm:followup:manage')")
    @EmitEvent(id = "CRM_FOLLOWUP_ASSIGN", apiVersion = "1")
    public ResponseEntity<FollowUpTaskResponse> assign(
            @PathVariable UUID taskId, @RequestParam(name = "assignedTo", required = false) String assignedTo) {
        return ResponseEntity.ok(followUpTaskService.assign(taskId, assignedTo));
    }

    @Operation(operationId = "completeFollowUpTask", summary = "Complete Follow-Up Task", description = """
                    Closes an open follow-up task as DONE, recording the outcome and any workorder or \
                    appointment that resulted from the touch.
                    Use this tool when the follow-up was actually worked; use dismissFollowUpTask instead \
                    when the task is being deliberately abandoned without contact.
                    Preconditions: the task must exist and still be OPEN; a closed task cannot be reopened, \
                    so a new follow-up must be raised to try again.
                    Required inputs: taskId (UUID) as a path parameter and outcome (non-blank, max 500) in \
                    the body; bookedWorkorderId and bookedAppointmentId are optional booking references.
                    Emits a CRM_FOLLOWUP_COMPLETE event and stamps closedAt and closedBy on the task.
                    Returns 404 when the task does not exist, 422 when it is already DONE or DISMISSED, and \
                    400 when outcome is blank.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Task completed",
                content = @Content(schema = @Schema(implementation = FollowUpTaskResponse.class))),
        @ApiResponse(responseCode = "404", description = "Task not found", content = @Content),
        @ApiResponse(responseCode = "422", description = "Task is already closed", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/follow-ups/{taskId}/complete")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.FOLLOWUP_MANAGE})
    @PreAuthorize("hasAuthority('crm:followup:manage')")
    @EmitEvent(id = "CRM_FOLLOWUP_COMPLETE", apiVersion = "1")
    public ResponseEntity<FollowUpTaskResponse> complete(
            @PathVariable UUID taskId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The worked outcome of the follow-up and any booking it produced.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Booked the declined work", value = """
                                                                    {"outcome":"Customer booked rear brake job for next week",
                                                                     "bookedWorkorderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5f"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CloseFollowUpTaskRequest request) {
        return ResponseEntity.ok(followUpTaskService.complete(taskId, request));
    }

    @Operation(operationId = "dismissFollowUpTask", summary = "Dismiss Follow-Up Task", description = """
                    Closes an open follow-up task as DISMISSED, recording why it is deliberately not being \
                    pursued.
                    Use this tool when a follow-up should be abandoned, for example because the customer \
                    already returned; use completeFollowUpTask instead when the touch was actually made.
                    Preconditions: the task must exist and still be OPEN; dismissal is final and a new task \
                    must be raised to revisit the customer.
                    Required inputs: taskId (UUID) as a path parameter and outcome (non-blank, max 500) in \
                    the body explaining the dismissal; booking references are accepted but normally absent.
                    Emits a CRM_FOLLOWUP_DISMISS event and stamps closedAt and closedBy on the task.
                    Returns 404 when the task does not exist, 422 when it is already DONE or DISMISSED, and \
                    400 when outcome is blank.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Task dismissed",
                content = @Content(schema = @Schema(implementation = FollowUpTaskResponse.class))),
        @ApiResponse(responseCode = "404", description = "Task not found", content = @Content),
        @ApiResponse(responseCode = "422", description = "Task is already closed", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/follow-ups/{taskId}/dismiss")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.FOLLOWUP_MANAGE})
    @PreAuthorize("hasAuthority('crm:followup:manage')")
    @EmitEvent(id = "CRM_FOLLOWUP_DISMISS", apiVersion = "1")
    public ResponseEntity<FollowUpTaskResponse> dismiss(
            @PathVariable UUID taskId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The reason this follow-up is being closed without being pursued.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "No longer relevant", value = """
                                                                    {"outcome":"Vehicle was sold; service no longer needed"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CloseFollowUpTaskRequest request) {
        return ResponseEntity.ok(followUpTaskService.dismiss(taskId, request));
    }
}
