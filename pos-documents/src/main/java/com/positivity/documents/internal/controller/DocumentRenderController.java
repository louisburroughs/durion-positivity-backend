package com.positivity.documents.internal.controller;

import com.positivity.documents.internal.dto.RenderRequest;
import com.positivity.documents.service.PdfRenderingService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/documents")
@Tag(name = "Document Render API", description = "Render source content into PDF output")
public class DocumentRenderController {

    private final PdfRenderingService pdfRenderingService;

    public DocumentRenderController(PdfRenderingService pdfRenderingService) {
        this.pdfRenderingService = pdfRenderingService;
    }

    @PostMapping(value = "/render", produces = MediaType.APPLICATION_PDF_VALUE)
    @EmitEvent(id = "DOCUMENT_RENDER", apiVersion = "1")
    @PreAuthorize("hasAuthority('documents:render')")
    @Operation(
            summary = "Render document to PDF",
            description = "Renders input content and template context into a PDF document.")
    @ApiResponse(
            responseCode = "200",
            description = "PDF rendered successfully",
            content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "400", description = "Invalid render request")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public ResponseEntity<byte[]> renderDocument(@RequestBody @Valid RenderRequest request) {
        byte[] pdfContent = pdfRenderingService.renderPdf(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=document.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfContent);
    }
}
