package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.internal.dto.ColumnMappingApproveRequest;
import com.positivity.bulkloader.internal.dto.ColumnMappingResponse;
import com.positivity.bulkloader.internal.security.BulkImportPermissions;
import com.positivity.bulkloader.internal.service.ColumnMappingService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"bulkImport:upload:execute", "bulkImport:status:read"})
@RequestMapping("/v1/bulk-jobs")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Column Mapping API", description = "Manage column mappings for bulk import jobs")
public class ColumnMappingController {

    private final ColumnMappingService columnMappingService;

    @GetMapping("/{jobId}/mappings")
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.STATUS_READ + "')")
    @Operation(operationId = "getColumnMappings", summary = "Get Proposed Column Mappings for Job", description = """
                    Returns the column mappings currently stored for a bulk load job, covering both auto-suggested \
                    mappings from content detection and operator-approved overrides.
                    Use this tool to review detected source-column to target-field mappings, their confidence and \
                    origin; use approveColumnMappings instead to finalize the set.
                    Preconditions: the job must exist and belong to the authenticated operator; mappings are \
                    typically present only after a file upload has triggered content detection.
                    Required inputs: jobId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when the job does not exist, and 403 when the job belongs to another operator.
                    """)
    @ApiResponse(responseCode = "200", description = "Mappings returned")
    @ApiResponse(
            responseCode = "403",
            description = "Job does not belong to the authenticated operator",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Job not found",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<ColumnMappingResponse>> getMappings(@PathVariable @NonNull UUID jobId) {
        String operatorId = currentOperatorId();
        return ResponseEntity.ok(columnMappingService.getMappingsForJob(jobId, operatorId));
    }

    @PutMapping("/{jobId}/mappings")
    @PreAuthorize("hasAuthority('" + BulkImportPermissions.UPLOAD_EXECUTE + "')")
    @EmitEvent(id = "BULK_LOADER_MAPPING_APPROVE", apiVersion = "1")
    @Operation(
            operationId = "approveColumnMappings",
            summary = "Approve and Finalize Column Mappings",
            description = """
                    Replaces all stored column mappings for a bulk load job with the submitted set, marking every \
                    mapping operator-approved with confidence 1.0.
                    Use this tool after reviewing the suggestions from getColumnMappings; do not use \
                    getColumnMappings, which only reads mappings, and note that the submitted list fully replaces \
                    prior mappings rather than merging with them.
                    Preconditions: the job must exist and belong to the authenticated operator; the job state is not \
                    checked, but approved mappings only affect processing started afterwards.
                    Required inputs: mappings, a non-empty list where each entry names a sourceColumn from the \
                    uploaded file and the targetField it maps to; mappingId is accepted but ignored because the set \
                    is replaced wholesale.
                    Emits a BULK_LOADER_MAPPING_APPROVE event and deletes previously stored mappings, including \
                    auto-suggested ones, before saving the new set.
                    Returns 404 when the job does not exist, and 403 when the job belongs to another operator.
                    """)
    @ApiResponse(responseCode = "200", description = "Mappings approved")
    @ApiResponse(
            responseCode = "403",
            description = "Job does not belong to the authenticated operator",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Job not found",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<ColumnMappingResponse>> approveMappings(
            @PathVariable @NonNull UUID jobId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Full replacement set of column mappings, one entry per source column to import.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Two approved mappings", value = """
                                                                    {"mappings":[
                                                                      {"sourceColumn":"Product SKU","targetField":"sku"},
                                                                      {"sourceColumn":"Retail Price","targetField":"price"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    ColumnMappingApproveRequest request) {
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
