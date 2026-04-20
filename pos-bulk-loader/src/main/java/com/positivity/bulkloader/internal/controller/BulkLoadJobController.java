package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.internal.dto.BulkLoadJobCreateRequest;
import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.service.BulkLoadJobService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/bulk-jobs")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Bulk Load Jobs API", description = "Manage bulk data import jobs")
public class BulkLoadJobController {

    private final BulkLoadJobService bulkLoadJobService;

    @PostMapping
    @PreAuthorize("hasAuthority('BULK_IMPORT_EXECUTE')")
    @EmitEvent(id = "BULK_LOADER_JOB_CREATE", apiVersion = "1")
    @Operation(summary = "Create a new bulk load job")
    @ApiResponse(responseCode = "201", description = "Job created")
    @ApiResponse(responseCode = "409", description = "Operator already has an active job")
    public ResponseEntity<BulkLoadJobResponse> createJob(
            @Valid @RequestBody @NonNull BulkLoadJobCreateRequest request) {
        String operatorId = currentOperatorId();
        BulkLoadJobResponse response = bulkLoadJobService.createJob(request, operatorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{jobId}")
    @PreAuthorize("hasAuthority('BULK_IMPORT_READ')")
    @Operation(summary = "Get a bulk load job by ID")
    @ApiResponse(responseCode = "200", description = "Job found")
    @ApiResponse(responseCode = "404", description = "Job not found")
    public ResponseEntity<BulkLoadJobResponse> getJob(@PathVariable @NonNull UUID jobId) {
        return ResponseEntity.ok(bulkLoadJobService.getJob(jobId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('BULK_IMPORT_READ')")
    @Operation(summary = "List bulk load jobs for the authenticated operator")
    @ApiResponse(responseCode = "200", description = "Jobs listed")
    public ResponseEntity<Page<BulkLoadJobResponse>> listJobs(@NonNull Pageable pageable) {
        String operatorId = currentOperatorId();
        return ResponseEntity.ok(bulkLoadJobService.listJobsForOperator(operatorId, pageable));
    }

    @PostMapping("/{jobId}/cancel")
    @PreAuthorize("hasAuthority('BULK_IMPORT_EXECUTE')")
    @EmitEvent(id = "BULK_LOADER_JOB_CANCEL", apiVersion = "1")
    @Operation(summary = "Cancel a running bulk load job")
    @ApiResponse(responseCode = "200", description = "Job cancelled")
    @ApiResponse(responseCode = "409", description = "Job is already in terminal state")
    public ResponseEntity<BulkLoadJobResponse> cancelJob(@PathVariable @NonNull UUID jobId) {
        String operatorId = currentOperatorId();
        return ResponseEntity.ok(bulkLoadJobService.cancelJob(jobId, operatorId));
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
