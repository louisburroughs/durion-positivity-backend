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

    @Operation(operationId = "downloadInvoiceArtifact", summary = "Download Invoice Artifact as PDF", description = """
                    Streams an invoice or receipt artifact as an application/pdf attachment, rendering it on demand \
                    through the document service.
                    Use this tool (or a browser direct link) with a token minted by createArtifactDownloadToken; \
                    do not call it with a bearer token alone — the endpoint is public and authorized solely by the \
                    signed download token.
                    Preconditions: a valid unexpired download token bound to exactly this invoiceId and \
                    artifactRefId, and the artifact must still belong to the invoice.
                    Required inputs: invoiceId (UUID) and artifactRefId as path parameters plus the token query \
                    parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only render of the stored document \
                    data.
                    Returns 403 when the token is missing, malformed, expired, or bound to a different artifact, and \
                    404 when the invoice or artifact cannot be resolved.
                    """)
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
