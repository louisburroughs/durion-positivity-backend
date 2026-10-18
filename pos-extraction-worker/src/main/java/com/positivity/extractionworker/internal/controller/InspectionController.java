package com.positivity.extractionworker.internal.controller;

import com.positivity.extractionworker.internal.dto.InspectionResponse;
import com.positivity.extractionworker.internal.enums.DeclaredType;
import com.positivity.extractionworker.internal.security.TenantLabelFilter;
import com.positivity.extractionworker.internal.security.WorkerSecretFilter;
import com.positivity.extractionworker.internal.service.InspectionService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The inspection endpoint: a verdict on one untrusted bill file. */
@RestController
@RequestMapping(WorkerSecretFilter.PATH_PREFIX)
@PreAuthorize("isAuthenticated()")
@Tag(name = "Inspection", description = "Verdicts on untrusted bill files; no page and no copy of the file is returned")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @Operation(
            summary = "Inspect an uploaded bill file",
            description = "Checks one uploaded file in a sandboxed child process and answers whether it may be read"
                    + " further, with its page count and SHA-256. Use this tool when a file has just been uploaded and"
                    + " must be judged before anything renders, tabulates or extracts it; do not use it to read"
                    + " values from a file, because it returns none and the extraction and tabulation operations do"
                    + " that instead. Preconditions: the caller holds the shared secret and names its tenant, and the"
                    + " file is at most 25 MiB. Required inputs: a multipart file part named file and a declaredType of"
                    + " PDF, JPEG, PNG, HEIC, TIFF, CSV or XLSX. No events are emitted and nothing is stored; the call"
                    + " can be repeated safely. Returns 413 when the file is over the size limit and 503 with"
                    + " Retry-After when every parse slot is busy or the sandbox cannot run.")
    @RequestBody(
            description = "The file to inspect and the type the caller says it is.",
            required = true,
            content =
                    @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            examples =
                                    @ExampleObject(name = "pdf", value = "file=<bytes of bill.pdf>; declaredType=PDF")))
    @ApiResponse(
            responseCode = "200",
            description = "The verdict. A rejected file is still a 200 with verdict REJECTED and a reasonCode.")
    @ApiResponse(
            responseCode = "400",
            description = "A part is missing or declaredType is not one of the allowed types.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "The secret is missing, wrong or unconfigured, or X-Tenant-Id is missing.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "413",
            description = "The file is over the size limit.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "WORKER_BUSY (every parse slot is busy) or WORKER_UNAVAILABLE (the sandbox could not run); retry after the Retry-After seconds.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping(value = "/inspections", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InspectionResponse inspect(
            @RequestParam("file") MultipartFile file,
            @RequestParam("declaredType") DeclaredType declaredType,
            @RequestAttribute(TenantLabelFilter.TENANT_ATTRIBUTE) String tenant)
            throws IOException {
        try (var in = file.getInputStream()) {
            return inspectionService.inspect(in, declaredType, tenant);
        }
    }
}
