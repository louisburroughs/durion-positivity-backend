package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * A freshly minted tenant impersonation token (ADR-0062 §7, WS2b-4). Returned exactly once: it
 * has no refresh half and expires 15 minutes after minting, so a longer session is a new mint.
 */
@Schema(description = "A 15-minute, read-only SUPPORT impersonation token for a tenant, returned once")
public record ImpersonationTokenResponse(
        @Schema(
                description = "The signed access token; send it as the bearer token. It carries tid = tenantId, "
                        + "the SUPPORT role's permissions, act = the operator and token_use = impersonation, "
                        + "and cannot be refreshed",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzdXBwb3J0OmFkbWluLnBsYXRmb3JtQGFjbWUifQ.sig",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String token,

        @Schema(
                description = "When the token stops working (15 minutes after minting); there is no refresh",
                example = "2026-09-10T12:15:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant expiresAt,

        @Schema(
                description = "The tenant the token acts inside (its tid claim)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID tenantId,

        @Schema(description = "That tenant's slug", example = "acme-tire", requiredMode = Schema.RequiredMode.REQUIRED)
        String tenantSlug) {}
