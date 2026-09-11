package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * A freshly minted tenant impersonation token (ADR-0062 §7, WS2b-4). Returned exactly once: it
 * has no refresh half and expires 15 minutes after minting, so a longer session is a new mint.
 */
@Schema(
        description = "A 15-minute, read-only SUPPORT impersonation token for a tenant, returned once. Its "
                + "permissions are the tenant's SUPPORT grants capped at the read-only ceiling, so a widened "
                + "SUPPORT role never yields a write-capable token")
public record ImpersonationTokenResponse(
        @Schema(
                description = "The signed access token; send it as the bearer token. It carries tid = tenantId, "
                        + "act = the operator and token_use = impersonation, and cannot be refreshed. Its "
                        + "perm_bits is the tenant's SUPPORT grants intersected with the read-only ceiling: "
                        + "only actions view and read, plus location:read; never a write, never platform:*, and "
                        + "never people:employee_pii:view, people:self:view, nlti:audit:read, nlti:request:read "
                        + "or the MCP administration surface (mcp:eval_trace:view, mcp:llm_api:view, "
                        + "mcp:system_prompt:view, mcp:tool:view). Anything the role grants beyond that is "
                        + "dropped and reported as droppedGrants on the audit events",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzdXBwb3J0OmFkbWluLnBsYXRmb3JtQGFjbWUifQ.sig",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String token,

        @Schema(
                description = "When the token stops working (15 minutes after minting); there is no refresh. "
                        + "It also stops working earlier if the operator is disabled or expired, or loses the "
                        + "role carrying platform:tenant:impersonate",
                example = "2026-09-10T12:15:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant expiresAt,

        @Schema(
                description = "The tenant the token acts inside (its tid claim)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID tenantId,

        @Schema(description = "That tenant's slug", example = "acme-tire", requiredMode = Schema.RequiredMode.REQUIRED)
        String tenantSlug) {}
