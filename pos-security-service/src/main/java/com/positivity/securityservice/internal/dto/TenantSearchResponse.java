package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One organization the login form may offer. Carries only what the form needs: the name to show and
 * the slug to submit. Never the tenant id, its status, its account or any count — this response is
 * served to anonymous callers (ADR-0062 §3).
 */
@Schema(description = "An organization a user may sign in to")
public record TenantSearchResponse(
        @Schema(description = "Tenant slug to submit with the login", example = "acme-tire")
        String slug,

        @Schema(description = "Human-readable organization name", example = "Acme Tire & Auto — Tucson")
        String displayName) {}
