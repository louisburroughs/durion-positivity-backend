package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Resolved party id to display name (and best-effort phone) pairing.
 */
@Schema(description = "Party id resolved to its display name and best-effort phone")
public record PartyNameRef(
        @Schema(
                description = "Party identifier",
                example = "550e8400-e29b-41d4-a716-446655440000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID partyId,

        @Schema(
                description = "Resolved display name (commercial display/legal name or person full name)",
                example = "Acme Towing LLC",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String displayName,

        @Schema(
                description = "Best-effort phone (person primary phone contact-point; null for commercial parties)",
                example = "+1-555-0100",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String phoneNumber) {}
