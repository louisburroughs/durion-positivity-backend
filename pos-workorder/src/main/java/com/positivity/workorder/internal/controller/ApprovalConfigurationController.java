package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.ApprovalConfigurationRequest;
import com.positivity.workorder.internal.dto.ApprovalConfigurationResponse;
import com.positivity.workorder.service.ApprovalConfigurationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Approval Configuration API", description = "Endpoints for managing approval configurations by location and customer")
@RestController
@RequestMapping("/v1/workexec")
@RequiredArgsConstructor
public class ApprovalConfigurationController {
    private final ApprovalConfigurationService approvalConfigurationService;

    @Operation(summary = "Get all approval configurations", description = "Retrieve a list of all approval configurations.")
    @ApiResponse(responseCode = "200", description = "List of configurations returned successfully.")
    @GetMapping
    @EmitEvent(id = "WORKORDER_APPROVAL_CONFIG_LIST", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:approval_config:view')")
    public List<ApprovalConfigurationResponse> getAllConfigurations() {
        return approvalConfigurationService.getAllConfigurations();
    }

    @Operation(summary = "Get configuration by ID", description = "Retrieve an approval configuration by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Configuration found and returned.")
    @ApiResponse(responseCode = "404", description = "Configuration not found.")
    @GetMapping("/approvalConfigurations/{approvalId}")
    @PreAuthorize("hasAuthority('workorder:approval_config:view')")
    public ResponseEntity<ApprovalConfigurationResponse> getConfigurationById(
            @Parameter(description = "ID of the configuration to retrieve", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID approvalId) {
        return approvalConfigurationService.getConfigurationById(approvalId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get applicable configuration", description = "Get the most specific configuration for a location and customer.")
    @ApiResponse(responseCode = "200", description = "Configuration found and returned.")
    @ApiResponse(responseCode = "404", description = "No configuration found (default will be used).")
    @GetMapping("/approvalConfigurations/applicable")
    @PreAuthorize("hasAuthority('workorder:approval_config:view')")
    public ResponseEntity<ApprovalConfigurationResponse> getApplicableConfiguration(
            @Parameter(description = "Location ID", example = "550e8400-e29b-41d4-a716-446655440020") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Customer ID", example = "550e8400-e29b-41d4-a716-446655440010") @RequestParam(required = false) UUID customerId) {
        return approvalConfigurationService.getApplicableConfiguration(locationId, customerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new approval configuration", description = "Add a new approval configuration.")
    @ApiResponse(responseCode = "200", description = "Configuration created successfully.")
    @PostMapping("/approvalConfigurations")
    @EmitEvent(id = "WORKORDER_APPROVAL_CONFIG_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:approval_config:create')")
    public ResponseEntity<ApprovalConfigurationResponse> createConfiguration(
            @Parameter(description = "Configuration object to be created") @RequestBody ApprovalConfigurationRequest request) {
        ApprovalConfigurationResponse created = approvalConfigurationService.createConfiguration(request);
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "Update an approval configuration", description = "Update an existing approval configuration.")
    @ApiResponse(responseCode = "200", description = "Configuration updated successfully.")
    @ApiResponse(responseCode = "404", description = "Configuration not found.")
    @PutMapping("/approvalConfigurations/{approvalId}")
    @EmitEvent(id = "WORKORDER_APPROVAL_CONFIG_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:approval_config:edit')")
    public ResponseEntity<ApprovalConfigurationResponse> updateConfiguration(
            @Parameter(description = "ID of the configuration to update", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID approvalId,
            @Parameter(description = "Updated configuration object") @RequestBody ApprovalConfigurationRequest request) {
        try {
            ApprovalConfigurationResponse updated = approvalConfigurationService.updateConfiguration(approvalId,
                    request);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Delete an approval configuration", description = "Delete a configuration by its unique ID.")
    @ApiResponse(responseCode = "204", description = "Configuration deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Configuration not found.")
    @DeleteMapping("/approvalConfigurations/{approvalId}")
    @EmitEvent(id = "WORKORDER_APPROVAL_CONFIG_DELETE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:approval_config:delete')")
    public ResponseEntity<Void> deleteConfiguration(
            @Parameter(description = "ID of the configuration to delete", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID approvalId) {
        approvalConfigurationService.deleteConfiguration(approvalId);
        return ResponseEntity.noContent().build();
    }
}
