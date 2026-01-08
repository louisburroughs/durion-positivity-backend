package com.positivity.workorder.controller;

import com.positivity.workorder.entity.ApprovalConfiguration;
import com.positivity.workorder.service.ApprovalConfigurationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Approval Configuration API", description = "Endpoints for managing approval configurations by location and customer")
@RestController
@RequestMapping("/api/approval-configurations")
@RequiredArgsConstructor
public class ApprovalConfigurationController {
    private final ApprovalConfigurationService approvalConfigurationService;

    @Operation(summary = "Get all approval configurations", description = "Retrieve a list of all approval configurations.")
    @ApiResponse(responseCode = "200", description = "List of configurations returned successfully.")
    @GetMapping
    public List<ApprovalConfiguration> getAllConfigurations() {
        return approvalConfigurationService.getAllConfigurations();
    }

    @Operation(summary = "Get configuration by ID", description = "Retrieve an approval configuration by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Configuration found and returned.")
    @ApiResponse(responseCode = "404", description = "Configuration not found.")
    @GetMapping("/{id}")
    public ResponseEntity<ApprovalConfiguration> getConfigurationById(
            @Parameter(description = "ID of the configuration to retrieve", example = "1") @PathVariable Long id) {
        return approvalConfigurationService.getConfigurationById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get applicable configuration", 
               description = "Get the most specific configuration for a location and customer.")
    @ApiResponse(responseCode = "200", description = "Configuration found and returned.")
    @ApiResponse(responseCode = "404", description = "No configuration found (default will be used).")
    @GetMapping("/applicable")
    public ResponseEntity<ApprovalConfiguration> getApplicableConfiguration(
            @Parameter(description = "Location ID") @RequestParam(required = false) Long locationId,
            @Parameter(description = "Customer ID") @RequestParam(required = false) Long customerId) {
        return approvalConfigurationService.getApplicableConfiguration(locationId, customerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new approval configuration", description = "Add a new approval configuration.")
    @ApiResponse(responseCode = "200", description = "Configuration created successfully.")
    @PostMapping
    public ResponseEntity<ApprovalConfiguration> createConfiguration(
            @Parameter(description = "Configuration object to be created") @RequestBody ApprovalConfiguration configuration) {
        ApprovalConfiguration created = approvalConfigurationService.createConfiguration(configuration);
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "Update an approval configuration", description = "Update an existing approval configuration.")
    @ApiResponse(responseCode = "200", description = "Configuration updated successfully.")
    @ApiResponse(responseCode = "404", description = "Configuration not found.")
    @PutMapping("/{id}")
    public ResponseEntity<ApprovalConfiguration> updateConfiguration(
            @Parameter(description = "ID of the configuration to update", example = "1") @PathVariable Long id,
            @Parameter(description = "Updated configuration object") @RequestBody ApprovalConfiguration configuration) {
        try {
            ApprovalConfiguration updated = approvalConfigurationService.updateConfiguration(id, configuration);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Delete an approval configuration", description = "Delete a configuration by its unique ID.")
    @ApiResponse(responseCode = "204", description = "Configuration deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Configuration not found.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConfiguration(
            @Parameter(description = "ID of the configuration to delete", example = "1") @PathVariable Long id) {
        approvalConfigurationService.deleteConfiguration(id);
        return ResponseEntity.noContent().build();
    }
}
