package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.internal.dto.FileUploadResponse;
import com.positivity.bulkloader.service.BulkLoadJobService;
import com.positivity.bulkloader.service.FileStorageService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.GatewaySecurityConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
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

    @PostMapping("/{jobId}/upload")
    @PreAuthorize("hasAuthority('bulkImport:upload:execute')")
    @EmitEvent(id = "BULK_LOADER_FILE_UPLOAD", apiVersion = "1")
    @Operation(
            summary = "Upload a file for a bulk load job",
            description =
                    "Uploads a file for the specified bulk load job. The file is stored and associated with the job for later processing. "
                            + "The job must be in CREATED or UPLOADING state. Multiple files can be uploaded, but only the latest file will be processed.")
    @ApiResponse(responseCode = "200", description = "File uploaded and content detected")
    @ApiResponse(responseCode = "404", description = "Job not found")
    public ResponseEntity<FileUploadResponse> uploadFile(
            @PathVariable @NonNull UUID jobId, @RequestParam("file") @NonNull MultipartFile file) throws IOException {
        String operatorId = currentOperatorId();
        bulkLoadJobService.getJob(jobId, operatorId);
        String originalFileName = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        String storagePath = fileStorageService.store(jobId, originalFileName, file.getInputStream(), file.getSize());
        bulkLoadJobService.markUploadStored(jobId, operatorId, storagePath);
        FileUploadResponse response = FileUploadResponse.builder()
                .jobId(jobId)
                .storagePath(storagePath)
                .fileName(originalFileName)
                .sizeBytes(file.getSize())
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
    @PreAuthorize("hasAuthority('bulkImport:upload:execute')")
    @EmitEvent(id = "BULK_LOADER_JOB_START", apiVersion = "1")
    @Operation(
            summary = "Launch a bulk load job for processing",
            description =
                    "Starts Spring Batch execution for the specified bulk load job and transitions it to PROCESSING. "
                            + "The job must be in CREATED, UPLOADING, or MAPPING_REVIEW state and must already have a persisted upload and locationId.")
    @ApiResponse(responseCode = "200", description = "Job transitioned to PROCESSING")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @ApiResponse(responseCode = "409", description = "Invalid state transition")
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
