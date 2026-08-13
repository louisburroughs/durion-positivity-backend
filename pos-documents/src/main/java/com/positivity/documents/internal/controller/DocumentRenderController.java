package com.positivity.documents.internal.controller;

import com.positivity.documents.internal.dto.RenderRequest;
import com.positivity.documents.service.PdfRenderingService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"documents:render"})
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
            operationId = "renderDocument",
            summary = "Render document to PDF",
            description = """
                    Renders the supplied source content into a PDF document and returns the PDF bytes inline.
                    Use this tool when a printable document is needed from structured content; do not use it to
                    store or retrieve documents, because nothing is persisted and every call re-renders from the
                    request alone.
                    Preconditions: none beyond an authenticated caller with documents:render; when templateId is
                    supplied the named template must exist.
                    Required inputs: format (JSON, XML, MARKDOWN, TEXT or CSV, describing the source content) and
                    content, the raw text to render; templateId is optional and applies the named layout.
                    Emits a DOCUMENT_RENDER event; no document record is created and the output exists only in the
                    response body.
                    Returns 400 when the format is missing or unsupported, 404 when the named template does not
                    exist, 422 when the content is blank or exceeds the maximum input size, and 500 when PDF
                    rendering itself fails.
                    """,
            tags = {"Document Render API"})
    @ApiResponse(
            responseCode = "200",
            description = "PDF rendered successfully",
            content =
                    @Content(
                            mediaType = "application/pdf",
                            schema = @Schema(type = "string", format = "binary", implementation = String.class)))
    @ApiResponse(responseCode = "400", description = "Invalid render request")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public ResponseEntity<byte[]> renderDocument(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Source content and target format to render into a PDF.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Invoice content with a template",
                                                            value =
                                                                    "{\"format\":\"JSON\",\"templateId\":\"invoice-default\","
                                                                            + "\"content\":\"{\\\"title\\\":\\\"Invoice\\\",\\\"total\\\":125.00}\"}")))
                    @RequestBody
                    @Valid
                    RenderRequest request) {
        byte[] pdfContent = pdfRenderingService.renderPdf(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=document.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfContent);
    }
}
