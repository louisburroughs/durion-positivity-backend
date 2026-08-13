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

    @Operation(operationId = "listInvoiceArtifacts", summary = "List Downloadable Invoice Artifacts", description = """
                    Returns the downloadable documents for an invoice — always the invoice document itself, plus one \
                    entry per generated receipt — as opaque URL-safe artifact references with suggested file names \
                    and application/pdf MIME types.
                    Use this tool to discover artifactRefId values before minting a download token; do not use \
                    downloadInvoiceArtifact directly, which requires a signed token from \
                    createArtifactDownloadToken.
                    Preconditions: the invoice must exist; receipts appear only after generateReceipt has created \
                    them.
                    Required inputs: invoiceId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no invoice exists for the supplied id.
                    """)
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
            operationId = "createArtifactDownloadToken",
            summary = "Create Artifact Download Token",
            description = """
                    Issues a short-lived signed token bound to one invoice artifact, so a browser can fetch the PDF \
                    through the public download link without an Authorization header.
                    Use this tool after listInvoiceArtifacts has supplied an artifactRefId; do not use \
                    downloadInvoiceArtifact without a token — it rejects unsigned requests.
                    Preconditions: the invoice must exist and the artifact reference must belong to that invoice \
                    (an invoice ref must match the path invoice, a receipt ref must be one of its receipts).
                    Required inputs: invoiceId (UUID) and artifactRefId (opaque reference from \
                    listInvoiceArtifacts) as path parameters; there is no request body.
                    No events are emitted and no record is stored; the token is stateless, signed, and expires after \
                    300 seconds by default (invoice.artifacts.token-ttl-seconds).
                    Returns 404 when the invoice does not exist or the artifact does not belong to it.
                    """)
    @ApiResponse(responseCode = "200", description = "Token issued")
    @ApiResponse(responseCode = "404", description = "Invoice or artifact not found")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{invoiceId}/artifacts/{artifactRefId}/download-token")
    public ResponseEntity<ArtifactDownloadTokenResponse> createDownloadToken(
            @PathVariable @NonNull UUID invoiceId, @PathVariable @NonNull String artifactRefId) {
        return ResponseEntity.ok(artifactService.createDownloadToken(invoiceId, artifactRefId));
    }
}
