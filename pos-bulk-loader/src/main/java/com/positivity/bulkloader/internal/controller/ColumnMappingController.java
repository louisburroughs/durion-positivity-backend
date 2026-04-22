package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.internal.dto.ColumnMappingApproveRequest;
import com.positivity.bulkloader.internal.dto.ColumnMappingResponse;
import com.positivity.bulkloader.service.ColumnMappingService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/bulk-jobs")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Column Mapping API", description = "Manage column mappings for bulk import jobs")
public class ColumnMappingController {

    private final ColumnMappingService columnMappingService;

    @GetMapping("/{jobId}/mappings")
    @PreAuthorize("hasAuthority('bulkImport:status:read')")
    @Operation(summary = "Get proposed column mappings for a job", description = "Retrieves the proposed column mappings for the specified bulk load job. The operator can only access mappings for their own jobs. This endpoint is typically used to review the detected column mappings before approving them for processing.")
    @ApiResponse(responseCode = "200", description = "Mappings returned")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    public ResponseEntity<List<ColumnMappingResponse>> getMappings(@PathVariable @NonNull UUID jobId) {
        String operatorId = currentOperatorId();
        return ResponseEntity.ok(columnMappingService.getMappingsForJob(jobId, operatorId));
    }

    @PutMapping("/{jobId}/mappings")
    @PreAuthorize("hasAuthority('bulkImport:upload:execute')")
    @EmitEvent(id = "BULK_LOADER_MAPPING_APPROVE", apiVersion = "1")
    @Operation(summary = "Approve and finalize column mappings for a job", description = "Approves and finalizes the proposed column mappings for the specified bulk load job. The operator can only approve mappings for their own jobs. Once approved, the mappings are locked and used for processing the bulk load job.")
    @ApiResponse(responseCode = "200", description = "Mappings approved")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    public ResponseEntity<List<ColumnMappingResponse>> approveMappings(
            @PathVariable @NonNull UUID jobId, @Valid @RequestBody @NonNull ColumnMappingApproveRequest request) {
        String operatorId = currentOperatorId();
        return ResponseEntity.ok(columnMappingService.approveMappings(jobId, operatorId, request));
    }

    private String currentOperatorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new IllegalStateException("Authenticated operator is required");
        }
        return authentication.getName();
    }
}
