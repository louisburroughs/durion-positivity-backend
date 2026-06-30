package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.dto.ArtifactDownloadTokenResponse;
import com.positivity.invoice.internal.dto.InvoiceArtifactResponse;
import com.positivity.invoice.internal.service.InvoiceArtifactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists invoice artifacts and mints short-lived download tokens. Both endpoints are
 * authenticated; the actual byte download is a separate, token-authorized public endpoint
 * (see {@code InvoiceArtifactDownloadController}).
 */
@Slf4j
@RestController
@RequestMapping("/v1/invoices")
@PreAuthorize("isAuthenticated()")
public class InvoiceArtifactController {

    private final InvoiceArtifactService artifactService;

    public InvoiceArtifactController(InvoiceArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @Operation(
            summary = "List the downloadable artifacts (documents) for an invoice",
            description =
                    "Returns the documents available for the invoice (the invoice itself and any receipts) as opaque, URL-safe artifact references with suggested file names and MIME types.")
    @ApiResponse(responseCode = "200", description = "Artifacts listed")
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{invoiceId}/artifacts")
    public ResponseEntity<List<InvoiceArtifactResponse>> listArtifacts(@PathVariable @NonNull UUID invoiceId) {
        log.info("listArtifacts request received for invoice {}", invoiceId);
        List<InvoiceArtifactResponse> artifacts = artifactService.listArtifacts(invoiceId);
        log.info("listArtifacts returning {} artifact(s) for invoice {}", artifacts.size(), invoiceId);
        return ResponseEntity.ok(artifacts);
    }

    @Operation(
            summary = "Create a short-lived download token for an invoice artifact",
            description =
                    "Issues a short-lived signed token bound to the invoice and artifact. The token is presented to the public download endpoint so a browser can fetch the file via a direct link.")
    @ApiResponse(responseCode = "200", description = "Token issued")
    @ApiResponse(responseCode = "404", description = "Invoice or artifact not found")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{invoiceId}/artifacts/{artifactRefId}/download-token")
    public ResponseEntity<ArtifactDownloadTokenResponse> createDownloadToken(
            @PathVariable @NonNull UUID invoiceId, @PathVariable @NonNull String artifactRefId) {
        return ResponseEntity.ok(artifactService.createDownloadToken(invoiceId, artifactRefId));
    }
}
