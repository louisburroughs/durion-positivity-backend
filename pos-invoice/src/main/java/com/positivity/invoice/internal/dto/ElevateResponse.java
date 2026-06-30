package com.positivity.invoice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

/**
 * Response payload carrying a minted manager-approval elevation token.
 *
 * <p>The token is passed back as {@code managerApprovalCode} on the subsequent
 * finalize request, where it is verified (signature, expiry, invoice scope).
 */
@Schema(description = "Minted manager-approval elevation token")
public class ElevateResponse {

    @Schema(
            description = "Signed, short-lived elevation token scoped to the invoice",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String elevationToken;

    @Schema(description = "Instant at which the token expires", requiredMode = Schema.RequiredMode.REQUIRED)
    private final Instant expiresAt;

    public ElevateResponse(@NonNull String elevationToken, @NonNull Instant expiresAt) {
        this.elevationToken = elevationToken;
        this.expiresAt = expiresAt;
    }

    @NonNull
    public String getElevationToken() {
        return elevationToken;
    }

    @NonNull
    public Instant getExpiresAt() {
        return expiresAt;
    }
}
