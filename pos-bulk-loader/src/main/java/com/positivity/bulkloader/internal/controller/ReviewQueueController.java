package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.internal.dto.AuditRecordResponse;
import com.positivity.bulkloader.service.BulkLoadJobService;
import com.positivity.bulkloader.service.ReviewQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/bulk-jobs")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Review Queue API", description = "Review and download bulk import audit records")
public class ReviewQueueController {

    private final BulkLoadJobService bulkLoadJobService;
    private final ReviewQueueService reviewQueueService;

    @GetMapping("/{jobId}/audit")
    @PreAuthorize("hasAuthority('BULK_IMPORT_READ')")
    @Operation(summary = "Get audit records for a bulk load job")
    @ApiResponse(responseCode = "200", description = "Audit records returned")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    public ResponseEntity<List<AuditRecordResponse>> getAuditRecords(@PathVariable @NonNull UUID jobId) {
        if (isNotOwner(jobId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(reviewQueueService.getAuditRecords(jobId));
    }

    @GetMapping("/{jobId}/error-report")
    @PreAuthorize("hasAuthority('BULK_IMPORT_READ')")
    @Operation(summary = "Download error report as CSV for a bulk load job")
    @ApiResponse(responseCode = "200", description = "CSV error report downloaded")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    public ResponseEntity<Resource> downloadErrorReport(@PathVariable @NonNull UUID jobId) {
        if (isNotOwner(jobId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Resource report = reviewQueueService.generateErrorReport(jobId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"error-report-" + jobId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(report);
    }

    private boolean isNotOwner(UUID jobId) {
        String operatorId = currentOperatorId();
        var job = bulkLoadJobService.getJob(jobId);
        return !job.getOperatorId().equals(operatorId);
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
