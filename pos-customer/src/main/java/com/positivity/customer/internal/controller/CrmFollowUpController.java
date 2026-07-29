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

    @Operation(
            summary = "Follow-up queue",
            description = "The CSR work queue, ordered by due date. All filters are optional.")
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

    @Operation(summary = "Get follow-up task", description = "Retrieve a single follow-up task")
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

    @Operation(summary = "List party follow-ups", description = "Follow-up tasks raised against a party")
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

    @Operation(summary = "Raise follow-up task", description = "Raise a follow-up task against a party")
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
            @PathVariable UUID partyId, @Valid @RequestBody CreateFollowUpTaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(followUpTaskService.create(partyId, request));
    }

    @Operation(summary = "Assign follow-up", description = "Assign or unassign a follow-up task")
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

    @Operation(
            summary = "Complete follow-up",
            description = "Close a follow-up as worked, recording the outcome and any resulting booking")
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
            @PathVariable UUID taskId, @Valid @RequestBody CloseFollowUpTaskRequest request) {
        return ResponseEntity.ok(followUpTaskService.complete(taskId, request));
    }

    @Operation(
            summary = "Dismiss follow-up",
            description = "Close a follow-up as deliberately not pursued, recording why")
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
            @PathVariable UUID taskId, @Valid @RequestBody CloseFollowUpTaskRequest request) {
        return ResponseEntity.ok(followUpTaskService.dismiss(taskId, request));
    }
}
