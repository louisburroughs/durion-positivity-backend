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
    @PreAuthorize("hasAuthority('BULK_IMPORT_READ')")
    @Operation(summary = "Get proposed column mappings for a job")
    @ApiResponse(responseCode = "200", description = "Mappings returned")
    public ResponseEntity<List<ColumnMappingResponse>> getMappings(@PathVariable @NonNull UUID jobId) {
        return ResponseEntity.ok(columnMappingService.getMappingsForJob(jobId));
    }

    @PutMapping("/{jobId}/mappings")
    @PreAuthorize("hasAuthority('BULK_IMPORT_EXECUTE')")
    @EmitEvent(id = "BULK_LOADER_MAPPING_APPROVE", apiVersion = "1")
    @Operation(summary = "Approve and finalize column mappings for a job")
    @ApiResponse(responseCode = "200", description = "Mappings approved")
    public ResponseEntity<List<ColumnMappingResponse>> approveMappings(
            @PathVariable @NonNull UUID jobId, @Valid @RequestBody @NonNull ColumnMappingApproveRequest request) {
        return ResponseEntity.ok(columnMappingService.approveMappings(jobId, request));
    }
}
