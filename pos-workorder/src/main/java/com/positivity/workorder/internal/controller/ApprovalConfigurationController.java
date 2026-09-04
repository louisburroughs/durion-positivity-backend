package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.ApprovalConfigurationRequest;
import com.positivity.workorder.internal.dto.ApprovalConfigurationResponse;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.ApprovalConfigurationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Approval Configuration API",
        description = "Endpoints for managing approval configurations by location and customer")
@RestController
@RequestMapping("/v1/workexec")
@RequiredArgsConstructor
public class ApprovalConfigurationController {
    private final ApprovalConfigurationService approvalConfigurationService;

    @Operation(
            operationId = "listApprovalConfigurations",
            summary = "List All Approval Configurations",
            description = """
                    Returns every approval configuration, covering location-specific, customer-specific, and \
                    global default rows that govern how estimate and workorder approvals are captured.
                    Use this tool when administering approval rules; use getApplicableApprovalConfiguration \
                    instead to resolve the single configuration that applies to one location and customer.
                    Preconditions: none beyond the caller holding workorder:approval_config:view.
                    Required inputs: none — there are no filters or pagination parameters.
                    Emits a WORKORDER_APPROVAL_CONFIG_LIST audit event; no configuration state changes — this is \
                    a read-only projection.
                    Returns 200 with the full list, possibly empty.
                    """)
    @ApiResponse(responseCode = "200", description = "List of configurations returned successfully.")
    @GetMapping
    @EmitEvent(id = "WORKORDER_APPROVAL_CONFIG_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:approval_config:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.APPROVAL_CONFIG_VIEW + "')")
    public List<ApprovalConfigurationResponse> getAllConfigurations() {
        return approvalConfigurationService.getAllConfigurations();
    }

    @Operation(
            operationId = "getApprovalConfiguration",
            summary = "Get Approval Configuration by Id",
            description = """
                    Returns a single approval configuration by its unique id.
                    Use this tool when the configuration id is already known; use \
                    getApplicableApprovalConfiguration instead to resolve which configuration governs a given \
                    location and customer.
                    Preconditions: the configuration must exist.
                    Required inputs: approvalId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no configuration exists for the id.
                    """)
    @ApiResponse(responseCode = "200", description = "Configuration found and returned.")
    @ApiResponse(responseCode = "404", description = "Configuration not found.")
    @GetMapping("/approvalConfigurations/{approvalId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:approval_config:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.APPROVAL_CONFIG_VIEW + "')")
    public ResponseEntity<ApprovalConfigurationResponse> getConfigurationById(
            @Parameter(
                            description = "ID of the configuration to retrieve",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID approvalId) {
        return approvalConfigurationService
                .getConfigurationById(approvalId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            operationId = "getApplicableApprovalConfiguration",
            summary = "Resolve Applicable Approval Configuration",
            description = """
                    Resolves the most specific approval configuration for a location and customer, trying the \
                    location-plus-customer row first, then the location-wide row, then the global default with \
                    no location or customer.
                    Use this tool when deciding how an approval must be captured for a specific job; use \
                    getApprovalConfiguration instead when the configuration id is already known.
                    Preconditions: none — both filters are optional and narrower matches win.
                    Required inputs: locationId (UUID) and customerId (UUID) as optional query parameters.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no configuration matches at any specificity, in which case callers fall \
                    back to built-in defaults.
                    """)
    @ApiResponse(responseCode = "200", description = "Configuration found and returned.")
    @ApiResponse(responseCode = "404", description = "No configuration found (default will be used).")
    @GetMapping("/approvalConfigurations/applicable")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:approval_config:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.APPROVAL_CONFIG_VIEW + "')")
    public ResponseEntity<ApprovalConfigurationResponse> getApplicableConfiguration(
            @Parameter(description = "Location ID", example = "550e8400-e29b-41d4-a716-446655440020")
                    @RequestParam(required = false)
                    UUID locationId,
            @Parameter(description = "Customer ID", example = "550e8400-e29b-41d4-a716-446655440010")
                    @RequestParam(required = false)
                    UUID customerId) {
        return approvalConfigurationService
                .getApplicableConfiguration(locationId, customerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            operationId = "createApprovalConfiguration",
            summary = "Create Approval Configuration",
            description = """
                    Creates an approval configuration defining how approvals are captured for a location, a \
                    location-customer pair, or globally when both scoping ids are omitted.
                    Use this tool when introducing a new approval rule; do not use updateApprovalConfiguration, \
                    which modifies an existing configuration by id.
                    Preconditions: none — duplicates for the same scope are not rejected, and the most specific \
                    row wins at resolution time.
                    Required inputs: approvalMethod, one of CLICK_CONFIRM, SIGNATURE, ELECTRONIC_SIGNATURE, or \
                    VERBAL_CONFIRMATION; locationId, customerId, declineExpiryDays, requireSignature, and \
                    priority are optional.
                    Emits a WORKORDER_APPROVAL_CONFIG_CREATE event.
                    Returns 200 with the persisted configuration, and 400 when approvalMethod is not one of the \
                    accepted values.
                    """)
    @ApiResponse(responseCode = "200", description = "Configuration created successfully.")
    @ApiResponse(responseCode = "400", description = "approvalMethod is not one of the accepted values.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Approval capture rule to create, scoped by optional location and customer ids.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = ApprovalConfigurationRequest.class),
                            examples =
                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                            name = "Location signature rule",
                                            value = """
                                                    {"locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a61",
                                                     "approvalMethod":"SIGNATURE",
                                                     "declineExpiryDays":14,
                                                     "requireSignature":true,
                                                     "priority":10}
                                                    """)))
    @PostMapping("/approvalConfigurations")
    @EmitEvent(id = "WORKORDER_APPROVAL_CONFIG_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:approval_config:create"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.APPROVAL_CONFIG_CREATE + "')")
    public ResponseEntity<ApprovalConfigurationResponse> createConfiguration(
            @Parameter(description = "Configuration object to be created") @RequestBody
                    ApprovalConfigurationRequest request) {
        ApprovalConfigurationResponse created = approvalConfigurationService.createConfiguration(request);
        return ResponseEntity.ok(created);
    }

    @Operation(
            operationId = "updateApprovalConfiguration",
            summary = "Update Approval Configuration",
            description = """
                    Replaces every field of an existing approval configuration with the values in the request, \
                    including nulling fields that are omitted.
                    Use this tool when changing an existing approval rule; do not use \
                    createApprovalConfiguration, which adds a new rule rather than replacing one.
                    Preconditions: the configuration must exist; this is a full replacement, so send all fields \
                    that should remain set.
                    Required inputs: approvalId (UUID) as a path parameter and approvalMethod (CLICK_CONFIRM, \
                    SIGNATURE, ELECTRONIC_SIGNATURE, or VERBAL_CONFIRMATION) in the body; locationId, \
                    customerId, declineExpiryDays, requireSignature, and priority are optional.
                    Emits a WORKORDER_APPROVAL_CONFIG_UPDATE event.
                    Returns 404 when the configuration does not exist, and 400 when approvalMethod is not one \
                    of the accepted values.
                    """)
    @ApiResponse(responseCode = "200", description = "Configuration updated successfully.")
    @ApiResponse(responseCode = "400", description = "approvalMethod is not one of the accepted values.")
    @ApiResponse(responseCode = "404", description = "Configuration not found.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Full replacement values for the approval configuration.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = ApprovalConfigurationRequest.class),
                            examples =
                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                            name = "Switch to electronic signature",
                                            value = """
                                                    {"locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a61",
                                                     "approvalMethod":"ELECTRONIC_SIGNATURE",
                                                     "declineExpiryDays":7,
                                                     "requireSignature":true,
                                                     "priority":10}
                                                    """)))
    @PutMapping("/approvalConfigurations/{approvalId}")
    @EmitEvent(id = "WORKORDER_APPROVAL_CONFIG_UPDATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:approval_config:edit"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.APPROVAL_CONFIG_EDIT + "')")
    public ResponseEntity<ApprovalConfigurationResponse> updateConfiguration(
            @Parameter(
                            description = "ID of the configuration to update",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID approvalId,
            @Parameter(description = "Updated configuration object") @RequestBody
                    ApprovalConfigurationRequest request) {
        ApprovalConfigurationResponse updated = approvalConfigurationService.updateConfiguration(approvalId, request);
        return ResponseEntity.ok(updated);
    }

    @Operation(
            operationId = "deleteApprovalConfiguration",
            summary = "Delete Approval Configuration",
            description = """
                    Deletes an approval configuration by id, after which resolution falls through to broader \
                    scopes or the global default.
                    Use this tool when retiring an approval rule; do not use updateApprovalConfiguration, which \
                    keeps the rule and changes its values.
                    Preconditions: none — deletion is idempotent, and deleting an id that does not exist is a \
                    silent no-op.
                    Required inputs: approvalId (UUID) as a path parameter; there is no request body.
                    Emits a WORKORDER_APPROVAL_CONFIG_DELETE event.
                    Returns 204 regardless of whether the configuration previously existed.
                    """)
    @ApiResponse(responseCode = "204", description = "Configuration deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Configuration not found.")
    @DeleteMapping("/approvalConfigurations/{approvalId}")
    @EmitEvent(id = "WORKORDER_APPROVAL_CONFIG_DELETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:approval_config:delete"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.APPROVAL_CONFIG_DELETE + "')")
    public ResponseEntity<Void> deleteConfiguration(
            @Parameter(
                            description = "ID of the configuration to delete",
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID approvalId) {
        approvalConfigurationService.deleteConfiguration(approvalId);
        return ResponseEntity.noContent().build();
    }
}
