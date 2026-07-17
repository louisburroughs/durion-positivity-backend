package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Response DTO for an AP vendor directory entry (Issue #816).
 * Used by the frontend vendor typeahead to resolve a vendor name to its
 * stable vendorId (and back, for deep-linked ids).
 */
@Value
@Builder
@Schema(description = "AP vendor directory entry (name to vendorId resolution)")
public class VendorResponse {

    /** Stable vendor identifier. */
    @Schema(
            description = "Stable vendor identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    UUID vendorId;

    /** Vendor display name. */
    @Schema(description = "Vendor display name", example = "Acme Auto Parts", requiredMode = REQUIRED)
    String name;

    /** Optional human-readable vendor number. */
    @Schema(
            description = "Human-readable vendor number, when assigned",
            example = "V-1042",
            requiredMode = NOT_REQUIRED)
    String vendorNumber;

    /** Vendor status. */
    @Schema(
            description = "Vendor status",
            example = "ACTIVE",
            allowableValues = {"ACTIVE", "INACTIVE"},
            requiredMode = NOT_REQUIRED)
    String status;
}
