package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** The caller's tenant as the {@code ext_tenant} replica knows it (ADR-0062 §7). */
@Schema(description = "The calling user's tenant")
public record TenantMeResponse(
        @Schema(description = "Tenant id, the token's tid claim", requiredMode = REQUIRED)
        UUID id,

        @Schema(description = "URL-safe unique name", example = "acme-tire", requiredMode = REQUIRED)
        String slug,

        @Schema(description = "Human-readable name", example = "Acme Tire & Auto", requiredMode = NOT_REQUIRED)
        String displayName,

        @Schema(description = "Lifecycle status: PENDING, ACTIVE, SUSPENDED or DECOMMISSIONED", requiredMode = REQUIRED)
        String status) {}
