package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.service.InvoiceArtifactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, token-authorized download of an invoice artifact as PDF.
 *
 * <p>This endpoint is intentionally not behind the gateway JWT (a browser cannot attach an
 * Authorization header to a direct download link). Authorization is enforced solely by the
 * short-lived signed token issued by {@code InvoiceArtifactController}; the path is permitted in
 * {@code ArtifactDownloadSecurityConfig} (downstream) and the gateway public-path list.
 */
@RestController
@RequestMapping("/v1/invoices")
public class InvoiceArtifactDownloadController {

    private final InvoiceArtifactService artifactService;

    public InvoiceArtifactDownloadController(InvoiceArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @Operation(
            summary = "Download an invoice artifact as PDF using a signed download token",
            description =
                    "Streams the artifact as application/pdf. Public endpoint authorized solely by the signed download token (query parameter); intended for browser direct-download links.")
    @ApiResponse(responseCode = "200", description = "PDF returned")
    @ApiResponse(responseCode = "403", description = "Missing, invalid, or expired token")
    @ApiResponse(responseCode = "404", description = "Invoice or artifact not found")
    // Authorization is the signed download token (verified in the service), not a role/JWT — a
    // browser direct-download link cannot carry an Authorization header. permitAll keeps the
    // endpoint open at the method-security layer; the token is the actual guard.
    @PreAuthorize("permitAll()")
    @GetMapping("/{invoiceId}/artifacts/{artifactRefId}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull String artifactRefId,
            @RequestParam("token") @NonNull String token) {
        byte[] pdf = artifactService.downloadArtifact(invoiceId, artifactRefId, token);
        String fileName = artifactService.fileNameFor(invoiceId, artifactRefId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
