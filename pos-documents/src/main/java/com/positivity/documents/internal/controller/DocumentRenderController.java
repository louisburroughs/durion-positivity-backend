package com.positivity.documents.internal.controller;

import com.positivity.documents.internal.dto.RenderRequest;
import com.positivity.documents.service.PdfRenderingService;
import com.positivity.events.EmitEvent;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class DocumentRenderController {

    private final PdfRenderingService pdfRenderingService;

    public DocumentRenderController(PdfRenderingService pdfRenderingService) {
        this.pdfRenderingService = pdfRenderingService;
    }

    @PostMapping(value = "/render", produces = MediaType.APPLICATION_PDF_VALUE)
    @EmitEvent(id = "DOCUMENT_RENDER", apiVersion = "1")
    public ResponseEntity<byte[]> renderDocument(@RequestBody @Valid RenderRequest request) {
        byte[] pdfContent = pdfRenderingService.renderPdf(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=document.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfContent);
    }
}
