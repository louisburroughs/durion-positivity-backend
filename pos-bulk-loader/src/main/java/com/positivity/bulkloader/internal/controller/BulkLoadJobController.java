package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.internal.dto.BulkLoadJobCreateRequest;
import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.internal.security.BulkImportPermissions;
import com.positivity.bulkloader.service.BulkLoadJobService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springdoc.core.annotations.ParameterObject;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"bulkImport:upload:execute", "bulkImport:status:read"})
@RequestMapping("/v1/bulk-jobs")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Bulk Load Jobs API", description = "Manage bulk data import jobs")
public class BulkLoadJobController {

    private final BulkLoadJobService bulkLoadJobService;

    @PostMapping
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.UPLOAD_EXECUTE + "')")
    @EmitEvent(id = "BULK_LOADER_JOB_CREATE", apiVersion = "1")
    @Operation(operationId = "createBulkLoadJob", summary = "Create a New Bulk Load Job", description = """
                    Creates a bulk load import job owned by the authenticated operator, starting in CREATED state \
                    with the target domain and expected file name recorded.
                    Use this tool when starting a new bulk import from a file; do not use uploadJobFile, which \
                    attaches the file to a job that already exists, and do not use retryBulkLoadJob, which re-queues \
                    a FAILED job.
                    Preconditions: the operator must have no other job in an active state (CREATED, UPLOADING, \
                    DETECTING, MAPPING_REVIEW, DEDUP or PROCESSING); only one active job per operator is allowed.
                    Required inputs: fileName (name of the source file that will be uploaded later) and domainType \
                    (one of CATALOG_PRODUCT, INVENTORY_STOCK_COUNT, LOCATION, CUSTOMER, PERSON, BASE_PRICE, VEHICLE \
                    or VEHICLE_FITMENT); locationId (UUID) is optional at creation but must be set before processing \
                    can start.
                    Emits a BULK_LOADER_JOB_CREATE event; no file content is stored by this call.
                    Returns 201 with the new job, and 409 when the operator already has an active bulk load job \
                    in progress.
                    """)
    @ApiResponse(responseCode = "201", description = "Job created")
    @ApiResponse(responseCode = "409", description = "Operator already has an active job")
    public ResponseEntity<BulkLoadJobResponse> createJob(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Bulk load job to create, naming the source file and the target import domain.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Catalog product import", value = """
                                                                    {"fileName":"products-2026-01.csv",
                                                                     "domainType":"CATALOG_PRODUCT",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkLoadJobCreateRequest request) {
        String operatorId = currentOperatorId();
        BulkLoadJobResponse response = bulkLoadJobService.createJob(request, operatorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{jobId}")
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.STATUS_READ + "')")
    @Operation(operationId = "getBulkLoadJob", summary = "Get a Bulk Load Job by ID", description = """
                    Returns the current state of a single bulk load job, including status, row counts and success \
                    and failure totals.
                    Use this tool to poll job progress after startJobProcessing, or whenever the job id is already \
                    known; use listBulkLoadJobs instead when the id is unknown.
                    Preconditions: the job must exist and belong to the authenticated operator; jobs owned by other \
                    operators are reported as not found rather than forbidden.
                    Required inputs: jobId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no job exists with the supplied id for the authenticated operator.
                    """)
    @ApiResponse(responseCode = "200", description = "Job found")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    @ApiResponse(responseCode = "404", description = "Job not found")
    public ResponseEntity<BulkLoadJobResponse> getJob(@PathVariable @NonNull UUID jobId) {
        String operatorId = currentOperatorId();
        return ResponseEntity.ok(bulkLoadJobService.getJob(jobId, operatorId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.STATUS_READ + "')")
    @Operation(operationId = "listBulkLoadJobs", summary = "List Bulk Load Jobs for Operator", description = """
                    Returns a paginated list of the authenticated operator's bulk load jobs with their statuses and \
                    progress counters.
                    Use this tool to find a job id or review import history; use getBulkLoadJob instead when a \
                    specific job id is already known.
                    Preconditions: none beyond authentication; the listing is always scoped to the caller's own jobs \
                    and other operators' jobs are never included.
                    Required inputs: standard page, size and sort query parameters, all optional with Spring \
                    defaults (page 0, size 20).
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty page when the operator has no jobs; there are no operation-specific \
                    error responses.
                    """)
    @ApiResponse(responseCode = "200", description = "Jobs listed")
    public ResponseEntity<Page<BulkLoadJobResponse>> listJobs(@ParameterObject @NonNull Pageable pageable) {
        String operatorId = currentOperatorId();
        return ResponseEntity.ok(bulkLoadJobService.listJobsForOperator(operatorId, pageable));
    }

    @PostMapping("/{jobId}/cancel")
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.UPLOAD_EXECUTE + "')")
    @EmitEvent(id = "BULK_LOADER_JOB_CANCEL", apiVersion = "1")
    @Operation(operationId = "cancelBulkLoadJob", summary = "Cancel a Running Bulk Load Job", description = """
                    Cancels an in-flight bulk load job by moving it to the terminal CANCELLED state.
                    Use this tool to abandon a job that is no longer wanted; do not use retryBulkLoadJob, which \
                    re-queues a FAILED job rather than stopping one.
                    Preconditions: the job must belong to the authenticated operator and must not already be in a \
                    terminal state (COMPLETED, CANCELLED or FAILED).
                    Required inputs: jobId (UUID) as a path parameter; there is no request body.
                    Emits a BULK_LOADER_JOB_CANCEL event and sets the job status to CANCELLED; rows already imported \
                    are not rolled back.
                    Returns 404 when the job does not exist, 403 when it belongs to another operator, and 409 when \
                    the job is already COMPLETED, CANCELLED or FAILED.
                    """)
    @ApiResponse(responseCode = "200", description = "Job cancelled")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @ApiResponse(responseCode = "409", description = "Job is already in terminal state")
    public ResponseEntity<BulkLoadJobResponse> cancelJob(@PathVariable @NonNull UUID jobId) {
        String operatorId = currentOperatorId();
        return ResponseEntity.ok(bulkLoadJobService.cancelJob(jobId, operatorId));
    }

    @PostMapping("/{jobId}/retry")
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.UPLOAD_EXECUTE + "')")
    @EmitEvent(id = "BULK_LOADER_JOB_RETRY", apiVersion = "1")
    @Operation(operationId = "retryBulkLoadJob", summary = "Retry a Failed Bulk Load Job", description = """
                    Resets a FAILED bulk load job back to CREATED and clears its progress counters so it can be \
                    processed again.
                    Use this tool after fixing the cause of a failure, typically via submitCorrections; do not use \
                    cancelBulkLoadJob, which terminates a job instead of re-queuing it.
                    Preconditions: the job must belong to the authenticated operator, must be in FAILED state, and \
                    the operator must have no other active job.
                    Required inputs: jobId (UUID) as a path parameter; there is no request body.
                    Emits a BULK_LOADER_JOB_RETRY event and resets startedAt, completedAt, totalRows and all row \
                    counters; processing does not start until startJobProcessing is called again.
                    Returns 404 when the job does not exist, 403 when it belongs to another operator, and 409 when \
                    the job is not in FAILED state or the operator already has an active job.
                    """)
    @ApiResponse(responseCode = "200", description = "Job reset and re-queued for retry")
    @ApiResponse(responseCode = "403", description = "Job does not belong to the authenticated operator")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @ApiResponse(responseCode = "409", description = "Job is not in FAILED state")
    public ResponseEntity<BulkLoadJobResponse> retryJob(
            @io.swagger.v3.oas.annotations.Parameter(
                            description = "ID of the failed bulk load job to retry",
                            required = true)
                    @PathVariable
                    @NonNull
                    UUID jobId) {
        String operatorId = currentOperatorId();
        return ResponseEntity.ok(bulkLoadJobService.retryJob(jobId, operatorId));
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
