package com.positivity.invoice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Short-lived, signed token authorizing a single artifact download.
 *
 * <p>The token is presented as a query parameter to the (public) download endpoint, so a
 * browser can fetch the file via a direct link without an Authorization header.
 */
@Schema(name = "ArtifactDownloadToken", description = "Short-lived signed token for downloading an invoice artifact")
public record ArtifactDownloadTokenResponse(
        @Schema(description = "Opaque signed download token") String downloadToken,
        @Schema(description = "When the token expires") Instant expiresAt) {}
