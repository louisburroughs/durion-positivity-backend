package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.internal.dto.FileUploadResponse;
import com.positivity.bulkloader.service.BulkLoadJobService;
import com.positivity.bulkloader.service.FileStorageService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/bulk-jobs")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Bulk Load File Upload API", description = "Upload files for bulk import")
public class FileUploadController {

    private final BulkLoadJobService bulkLoadJobService;
    private final FileStorageService fileStorageService;

    @PostMapping("/{jobId}/upload")
    @PreAuthorize("hasAuthority('BULK_IMPORT_EXECUTE')")
    @EmitEvent(id = "BULK_LOADER_FILE_UPLOAD", apiVersion = "1")
    @Operation(summary = "Upload a file for a bulk load job")
    @ApiResponse(responseCode = "200", description = "File uploaded and content detected")
    @ApiResponse(responseCode = "404", description = "Job not found")
    public ResponseEntity<FileUploadResponse> uploadFile(
            @PathVariable @NonNull UUID jobId, @RequestParam("file") @NonNull MultipartFile file) throws IOException {
        String operatorId = currentOperatorId();
        bulkLoadJobService.getJob(jobId, operatorId);
        String originalFileName = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        String storagePath = fileStorageService.store(jobId, originalFileName, file.getInputStream(), file.getSize());
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
    @PreAuthorize("hasAuthority('BULK_IMPORT_EXECUTE')")
    @EmitEvent(id = "BULK_LOADER_JOB_START", apiVersion = "1")
    @Operation(summary = "Transition a bulk load job to PROCESSING state", description = "Marks the job as PROCESSING. Batch execution is triggered separately by the batch runner. "
            +
            "The job must be in CREATED, UPLOADING, or MAPPING_REVIEW state.")
    @ApiResponse(responseCode = "200", description = "Job transitioned to PROCESSING")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @ApiResponse(responseCode = "409", description = "Invalid state transition")
    public ResponseEntity<BulkLoadJobResponse> startProcessing(@PathVariable @NonNull UUID jobId) {
        String operatorId = currentOperatorId();
        bulkLoadJobService.startProcessing(jobId, operatorId);
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
}
