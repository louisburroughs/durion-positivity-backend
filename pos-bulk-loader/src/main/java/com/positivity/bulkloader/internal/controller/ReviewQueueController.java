package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.internal.dto.AuditRecordResponse;
import com.positivity.bulkloader.internal.dto.BulkCorrectionItem;
import com.positivity.bulkloader.internal.dto.BulkCorrectionRequest;
import com.positivity.bulkloader.internal.dto.BulkCorrectionResponse;
import com.positivity.bulkloader.internal.dto.CorrectionResultDto;
import com.positivity.bulkloader.service.BulkLoadJobService;
import com.positivity.bulkloader.service.ReviewQueueService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"bulkImport:upload:execute", "bulkImport:status:read"})
@RequestMapping("/v1/bulk-jobs")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Review Queue API", description = "Review and download bulk import audit records")
public class ReviewQueueController {

    private final BulkLoadJobService bulkLoadJobService;
    private final ReviewQueueService reviewQueueService;

    @GetMapping("/{jobId}/audit")
    @PreAuthorize("hasAuthority('bulkImport:status:read')")
    @Operation(
            summary = "Get audit records for a bulk load job",
            description =
                    "Returns a list of audit records for the specified bulk load job, including review status and details for each record.")
    @ApiResponse(responseCode = "200", description = "Audit records returned")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    public ResponseEntity<List<AuditRecordResponse>> getAuditRecords(@PathVariable @NonNull UUID jobId) {
        bulkLoadJobService.getJob(jobId, currentOperatorId());
        return ResponseEntity.ok(reviewQueueService.getAuditRecords(jobId));
    }

    @GetMapping("/{jobId}/error-report")
    @PreAuthorize("hasAuthority('bulkImport:status:read')")
    @Operation(
            summary = "Download error report as CSV for a bulk load job",
            description =
                    "Generates and downloads a CSV file containing all error records for the specified bulk load job.")
    @ApiResponse(responseCode = "200", description = "CSV error report downloaded")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    public ResponseEntity<Resource> downloadErrorReport(@PathVariable @NonNull UUID jobId) {
        bulkLoadJobService.getJob(jobId, currentOperatorId());
        Resource report = reviewQueueService.generateErrorReport(jobId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"error-report-" + jobId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(report);
    }

    @PostMapping("/{jobId}/corrections")
    @PreAuthorize("hasAuthority('bulkImport:upload:execute')")
    @EmitEvent(id = "BULK_LOADER_CORRECTION_SUBMIT", apiVersion = "1")
    @Operation(
            summary = "Submit corrected records for a bulk load job",
            description =
                    "Submits corrected data for one or more error records from a bulk import audit. The job must be in FAILED state to accept corrections. Returns 409 if the job is not in a correctable state.")
    @ApiResponse(responseCode = "201", description = "Corrections submitted successfully")
    @ApiResponse(responseCode = "400", description = "Invalid correction request")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @ApiResponse(responseCode = "409", description = "Job is not in a state that accepts corrections")
    public ResponseEntity<BulkCorrectionResponse> submitCorrections(
            @PathVariable @NonNull UUID jobId, @Valid @RequestBody @NonNull BulkCorrectionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reviewQueueService.submitCorrections(jobId, request, currentOperatorId()));
    }

    @PostMapping("/{jobId}/corrections/single")
    @PreAuthorize("hasAuthority('bulkImport:upload:execute')")
    @EmitEvent(id = "BULK_LOADER_CORRECTION_SUBMIT_SINGLE", apiVersion = "1")
    @Operation(
            operationId = "submitSingleCorrection",
            summary = "Submit a single correction record",
            description =
                    "Submits a corrected data record for a single failed audit entry from a bulk import job. The job must be in FAILED state. Returns the acceptance or rejection status for the submitted record.")
    @ApiResponse(
            responseCode = "201",
            description = "Correction processed",
            content =
                    @io.swagger.v3.oas.annotations.media.Content(
                            schema =
                                    @io.swagger.v3.oas.annotations.media.Schema(
                                            implementation = CorrectionResultDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid correction request")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @ApiResponse(responseCode = "409", description = "Job is not in a state that accepts corrections")
    public ResponseEntity<CorrectionResultDto> submitSingleCorrection(
            @io.swagger.v3.oas.annotations.Parameter(description = "ID of the bulk load job", required = true)
                    @PathVariable
                    @NonNull
                    UUID jobId,
            @Valid @RequestBody @NonNull BulkCorrectionItem item) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reviewQueueService.submitSingleCorrection(jobId, item, currentOperatorId()));
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
