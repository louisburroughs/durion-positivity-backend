package com.positivity.extractionworker.internal.controller;

import com.positivity.extractionworker.internal.enums.DeclaredType;
import com.positivity.extractionworker.internal.security.TenantLabelFilter;
import com.positivity.extractionworker.internal.security.WorkerSecretFilter;
import com.positivity.extractionworker.internal.service.RenderingService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The rendering endpoint: one page of a document as a metadata-free PNG. */
@RestController
@RequestMapping(WorkerSecretFilter.PATH_PREFIX)
@PreAuthorize("isAuthenticated()")
@Tag(name = "Rendering", description = "One page of an uploaded document as a PNG, drawn in a sandboxed child process")
public class RenderingController {

    private final RenderingService renderingService;

    public RenderingController(RenderingService renderingService) {
        this.renderingService = renderingService;
    }

    @Operation(
            summary = "Render one page of a bill file",
            description = "Draws one page of an uploaded document as a PNG with all metadata stripped, inside a"
                    + " sandboxed child process, at no more than the configured pixel limit. Use this tool when a"
                    + " reviewer opens a page preview and no stored copy of that page exists yet; do not use it for"
                    + " CSV or XLSX files, and do not use it to read values, which the extraction operation does"
                    + " instead. Preconditions: the caller holds the shared secret and names its tenant, and the file"
                    + " has already passed inspection. Required inputs: a multipart file part named file, a"
                    + " declaredType of PDF, JPEG, PNG, HEIC or TIFF, and a 1-based page. No events are emitted and"
                    + " nothing is stored. Returns 422 when the page is past the last page or cannot be rendered.")
    @RequestBody(
            description = "The file, the type the caller says it is, and the 1-based page to draw.",
            required = true,
            content =
                    @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            examples =
                                    @ExampleObject(
                                            name = "page two of a pdf",
                                            value = "file=<bytes of bill.pdf>; declaredType=PDF; page=2")))
    @ApiResponse(
            responseCode = "200",
            description = "The page as image/png.",
            content =
                    @Content(
                            mediaType = MediaType.IMAGE_PNG_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(
            responseCode = "400",
            description = "A part is missing, declaredType is not renderable, or page is below 1.",
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
            responseCode = "422",
            description = "PAGE_NOT_RENDERABLE: the page does not exist or cannot be rendered.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Every parse slot is busy; retry after the Retry-After seconds.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping(
            value = "/renderings",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> render(
            @RequestParam("file") MultipartFile file,
            @RequestParam("declaredType") DeclaredType declaredType,
            @RequestParam("page") int page,
            @RequestAttribute(TenantLabelFilter.TENANT_ATTRIBUTE) String tenant)
            throws IOException {
        byte[] png;
        try (var in = file.getInputStream()) {
            png = renderingService.render(in, declaredType, page, tenant);
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }
}
