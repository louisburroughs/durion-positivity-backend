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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
    @Operation(operationId = "listAuditRecords", summary = "Get Audit Records for Job", description = """
                    Returns every row-level audit record captured for a bulk load job, including review status, \
                    reason codes and the original source values.
                    Use this tool to inspect which rows failed and why after processing; use downloadErrorReport \
                    instead when a CSV export of the same records is needed.
                    Preconditions: the job must exist and belong to the authenticated operator; audit records exist \
                    only after processing has run.
                    Required inputs: jobId (UUID) as a path parameter; there is no request body and no pagination, \
                    so the full list is returned in one response.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no job exists with the supplied id for the authenticated operator.
                    """)
    @ApiResponse(responseCode = "200", description = "Audit records returned")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    @ApiResponse(responseCode = "404", description = "Job not found")
    public ResponseEntity<List<AuditRecordResponse>> getAuditRecords(@PathVariable @NonNull UUID jobId) {
        bulkLoadJobService.getJob(jobId, currentOperatorId());
        return ResponseEntity.ok(reviewQueueService.getAuditRecords(jobId));
    }

    @GetMapping("/{jobId}/error-report")
    @PreAuthorize("hasAuthority('bulkImport:status:read')")
    @Operation(operationId = "downloadErrorReport", summary = "Download Error Report as CSV", description = """
                    Generates a CSV export of the audit records for a bulk load job and returns it as a text/csv \
                    attachment.
                    Use this tool to hand failed rows to a spreadsheet for offline correction; use listAuditRecords \
                    instead when the records are needed as JSON for programmatic handling.
                    Preconditions: the job must exist and belong to the authenticated operator.
                    Required inputs: jobId (UUID) as a path parameter; the response is a CSV with header columns \
                    row_number, entity_type, review_status, reason_codes and original_values, covering every audit \
                    record for the job rather than only errored rows.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no job exists with the supplied id for the authenticated operator.
                    """)
    @ApiResponse(responseCode = "200", description = "CSV error report downloaded")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    @ApiResponse(responseCode = "404", description = "Job not found")
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
    @Operation(operationId = "submitCorrections", summary = "Submit Corrected Records for Job", description = """
                    Submits corrected field values for one or more audit records of a FAILED bulk load job, marking \
                    each accepted record CORRECTED.
                    Use this tool to fix several failed rows in one call; use submitSingleCorrection instead when \
                    correcting exactly one record and a per-record accept or reject status is wanted.
                    Preconditions: the job must belong to the authenticated operator and be in FAILED state; each \
                    auditRecordId must belong to that job.
                    Required inputs: corrections, a non-empty list where each item carries auditRecordId (UUID) and \
                    correctedData, a map of field names to corrected string values.
                    Emits a BULK_LOADER_CORRECTION_SUBMIT event and stores the corrected values on each audit \
                    record; items whose audit record is missing or belongs to another job are rejected individually \
                    and reported in the response's rejections list without failing the call.
                    Correcting records does not re-run the import; call retryBulkLoadJob and then startJobProcessing \
                    to process the job again.
                    Returns 201 with accepted and rejected counts, 409 when the job is not in FAILED state, 404 \
                    when the job does not exist, and 403 when it belongs to another operator.
                    """)
    @ApiResponse(responseCode = "201", description = "Corrections submitted successfully")
    @ApiResponse(responseCode = "400", description = "Invalid correction request")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @ApiResponse(responseCode = "409", description = "Job is not in a state that accepts corrections")
    public ResponseEntity<BulkCorrectionResponse> submitCorrections(
            @PathVariable @NonNull UUID jobId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Batch of corrected values, one item per failed audit record to be marked CORRECTED.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Correct one failed row", value = """
                                                                    {"corrections":[
                                                                      {"auditRecordId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                       "correctedData":{"sku":"PROD-001","price":"19.99"}}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkCorrectionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reviewQueueService.submitCorrections(jobId, request, currentOperatorId()));
    }

    @PostMapping("/{jobId}/corrections/single")
    @PreAuthorize("hasAuthority('bulkImport:upload:execute')")
    @EmitEvent(id = "BULK_LOADER_CORRECTION_SUBMIT_SINGLE", apiVersion = "1")
    @Operation(operationId = "submitSingleCorrection", summary = "Submit a Single Correction Record", description = """
                    Submits corrected field values for exactly one audit record of a FAILED bulk load job and \
                    reports whether the correction was accepted.
                    Use this tool for interactive row-by-row fixing; use submitCorrections instead to correct a \
                    batch of records in one call.
                    Preconditions: the job must belong to the authenticated operator and be in FAILED state; the \
                    auditRecordId must belong to that job.
                    Required inputs: auditRecordId (UUID) and correctedData, a map of field names to corrected \
                    string values.
                    Emits a BULK_LOADER_CORRECTION_SUBMIT_SINGLE event and stores the corrected values on the audit \
                    record, setting its review status to CORRECTED when accepted; correcting a record does not \
                    re-run the import.
                    Returns 201 with status ACCEPTED or REJECTED plus a rejectionReason when rejected, 409 when the \
                    job is not in FAILED state, 404 when the job does not exist, and 403 when it belongs to another \
                    operator.
                    """)
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Single corrected record naming the audit record and the field values that replace the failed ones.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Corrected row", value = """
                                                                    {"auditRecordId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "correctedData":{"sku":"PROD-001","price":"19.99"}}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkCorrectionItem item) {
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
