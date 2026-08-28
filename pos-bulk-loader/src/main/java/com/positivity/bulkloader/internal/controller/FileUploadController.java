package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.internal.dto.ContentDetectionResult;
import com.positivity.bulkloader.internal.dto.FileUploadResponse;
import com.positivity.bulkloader.internal.security.BulkImportPermissions;
import com.positivity.bulkloader.internal.service.BulkLoadJobService;
import com.positivity.bulkloader.internal.service.ColumnMappingService;
import com.positivity.bulkloader.internal.service.FileStorageService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.GatewaySecurityConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"bulkImport:upload:execute"})
@RequestMapping("/v1/bulk-jobs")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Bulk Load File Upload API", description = "Upload files for bulk import")
public class FileUploadController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final BulkLoadJobService bulkLoadJobService;
    private final FileStorageService fileStorageService;
    private final ColumnMappingService columnMappingService;

    @PostMapping("/{jobId}/upload")
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.UPLOAD_EXECUTE + "')")
    @EmitEvent(id = "BULK_LOADER_FILE_UPLOAD", apiVersion = "1")
    @Operation(
            operationId = "uploadJobFile",
            summary = "Upload a File for Bulk Load Job",
            description = """
                    Uploads the source data file for a bulk load job as a single multipart request, stores it, and \
                    runs content detection to propose column mappings.
                    Use this tool for files small enough to send in one request; use createTusUpload instead for \
                    large files that need resumable, chunked upload.
                    Preconditions: the job must exist, belong to the authenticated operator, and not be in a \
                    terminal state (COMPLETED, CANCELLED or FAILED); a CREATED job moves to UPLOADING.
                    Required inputs: a multipart form part named file; formats understood by content detection are \
                    csv, tsv, txt, psv, xlsx, xlsm, xls, json, xml, yaml and yml, with the first row or record \
                    treated as the column headers.
                    Emits a BULK_LOADER_FILE_UPLOAD event and persists suggested column mappings from detection; \
                    when detection fails the upload still succeeds with a null detection field in the response, and \
                    re-uploading replaces the file to be processed because only the latest upload is used.
                    Returns 404 when the job does not exist for the authenticated operator, and 409 when the job is \
                    already in a terminal state.
                    """,
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Multipart form with a single part named file carrying the source data file to import.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "multipart/form-data",
                                            schemaProperties =
                                                    @SchemaProperty(
                                                            name = "file",
                                                            schema = @Schema(type = "string", format = "binary")),
                                            examples =
                                                    @ExampleObject(
                                                            name = "CSV upload",
                                                            value =
                                                                    "file=@products-2026-01.csv (binary content; first row is the header row)"))))
    @ApiResponse(responseCode = "200", description = "File uploaded and content detected")
    @ApiResponse(
            responseCode = "404",
            description = "Job not found",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Job is in a terminal state and cannot accept uploads",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<FileUploadResponse> uploadFile(
            @PathVariable @NonNull UUID jobId, @RequestParam("file") @NonNull MultipartFile file) throws IOException {
        String operatorId = currentOperatorId();
        bulkLoadJobService.getJob(jobId, operatorId);
        String originalFileName = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        String storagePath = fileStorageService.store(jobId, originalFileName, file.getInputStream(), file.getSize());
        bulkLoadJobService.markUploadStored(jobId, operatorId, storagePath);
        ContentDetectionResult detection = null;
        try {
            detection = columnMappingService.detectUploadedFile(jobId, operatorId, storagePath, originalFileName);
        } catch (RuntimeException ex) {
            log.warn(
                    "Content detection failed for job {} file {}; upload succeeded anyway",
                    jobId,
                    originalFileName,
                    ex);
        }
        FileUploadResponse response = FileUploadResponse.builder()
                .jobId(jobId)
                .storagePath(storagePath)
                .fileName(originalFileName)
                .sizeBytes(file.getSize())
                .detection(detection)
                .build();
        log.info(
                "File uploaded for job {} by operator {}: {} ({} bytes)",
                jobId,
                operatorId,
                originalFileName,
                file.getSize());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{jobId}/process")
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.UPLOAD_EXECUTE + "')")
    @EmitEvent(id = "BULK_LOADER_JOB_START", apiVersion = "1")
    @Operation(operationId = "startJobProcessing", summary = "Launch a Bulk Load Job", description = """
                    Starts asynchronous Spring Batch processing for a bulk load job and transitions it to PROCESSING.
                    Use this tool once the uploaded file is persisted and the mappings are acceptable; do not treat \
                    the 200 response as import completion, and poll getBulkLoadJob instead because the batch run \
                    continues in the background.
                    Preconditions: the job must belong to the authenticated operator, be in CREATED, UPLOADING or \
                    MAPPING_REVIEW state, and already have both a persisted uploaded file and a locationId assigned.
                    Required inputs: jobId (UUID) as a path parameter; there is no request body, and the caller's \
                    Authorization bearer token is forwarded to downstream domain services for the row-level writes.
                    Emits a BULK_LOADER_JOB_START event, launches the Spring Batch job, and stamps startedAt; row \
                    counters on the job update as chunks are processed.
                    Returns 404 when the job does not exist, 403 when it belongs to another operator, and 409 when \
                    the state is not launchable or the uploaded file or locationId is missing.
                    """)
    @ApiResponse(responseCode = "200", description = "Job transitioned to PROCESSING")
    @ApiResponse(
            responseCode = "403",
            description = "Job does not belong to the authenticated operator",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Job not found",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Invalid state transition",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<BulkLoadJobResponse> startProcessing(@PathVariable @NonNull UUID jobId) {
        String operatorId = currentOperatorId();
        bulkLoadJobService.startProcessing(jobId, operatorId, currentAuthorizationHeader());
        log.info("Bulk load job processing started: jobId={}, operatorId={}", jobId, operatorId);
        return ResponseEntity.ok(bulkLoadJobService.getJob(jobId, operatorId));
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

    private String currentAuthorizationHeader() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes requestAttributes)) {
            return null;
        }

        String authorizationHeader = requestAttributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            return authorizationHeader;
        }

        String gatewayTokenHeader = requestAttributes.getRequest().getHeader(GatewaySecurityConstants.HEADER_TOKEN);
        if (gatewayTokenHeader == null || gatewayTokenHeader.isBlank()) {
            return null;
        }

        return gatewayTokenHeader.startsWith(BEARER_PREFIX) ? gatewayTokenHeader : BEARER_PREFIX + gatewayTokenHeader;
    }
}
