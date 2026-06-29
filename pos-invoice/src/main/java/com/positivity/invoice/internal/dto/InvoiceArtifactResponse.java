package com.positivity.invoice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A downloadable document associated with an invoice (the invoice itself or a receipt).
 *
 * <p>{@code artifactRefId} is an opaque, URL-safe identifier; pass it back to the
 * download-token and download endpoints. The bytes are rendered on demand by pos-documents.
 */
@Schema(name = "InvoiceArtifact", description = "A downloadable document associated with an invoice")
public record InvoiceArtifactResponse(
        @Schema(description = "Opaque, URL-safe artifact reference", example = "aW52b2ljZTo...")
        String artifactRefId,

        @Schema(description = "Suggested download file name", example = "Invoice-INV-1001.pdf")
        String fileName,

        @Schema(description = "MIME type of the rendered artifact", example = "application/pdf")
        String mimeType,

        @Schema(description = "When the underlying document was created")
        Instant createdAt) {}
