package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Resolved party id to display name pairing.
 */
@Schema(description = "Party id resolved to its display name")
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
        String displayName) {}
