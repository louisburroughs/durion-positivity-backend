package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A freshly minted first-administrator activation token (ADR-0062 §7, WS2b-3). Returned exactly
 * once: only its hash is stored, so a lost token is replaced by minting another, never recovered.
 */
@Schema(description = "A one-time activation token for a tenant's first administrator, returned once")
public record ActivationTokenResponse(
        @Schema(
                description = "The token to hand to the administrator out of band; exchanged at POST /v1/auth/activate",
                example = "Qm9iIGlzIG5vdCBhIHJlYWwgdG9rZW4gYnV0IGxvb2tzIGxpa2Ugb25l",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String token,

        @Schema(
                description = "When the token stops being exchangeable (72 hours after minting)",
                example = "2026-09-13T12:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant expiresAt) {}
