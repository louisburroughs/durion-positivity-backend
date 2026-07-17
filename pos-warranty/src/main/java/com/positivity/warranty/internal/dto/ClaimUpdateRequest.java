package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.ClaimType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Edit payload for a claim in {@code DRAFT} or {@code INFO_NEEDED} (PRD §8). Origin, failure,
 * and location fields are full-update semantics (null clears); {@code claimType} and
 * {@code photoEvidenceUrls} are applied only when non-null (photos also have dedicated
 * add/remove endpoints). Lines are managed through the line add/remove endpoints.
 */
@Schema(description = "Update a warranty claim while DRAFT or INFO_NEEDED")
public record ClaimUpdateRequest(
        @Schema(description = "Claim type; unchanged when null")
        ClaimType claimType,

        @Schema(description = "Location where the claim is handled")
        UUID locationId,

        @Schema(description = "Originating workorder, when the sale was located")
        UUID originWorkorderId,

        @Schema(description = "Originating invoice, when the sale was located")
        UUID originInvoiceId,

        @Schema(description = "Original sale date (manual entry allowed for walk-ins)")
        LocalDate originSaleDate,

        @Schema(description = "Registration id for registration-bound coverage")
        UUID registrationId,

        @Schema(description = "What failed and how") @Size(max = 10_000)
        String failureDescription,

        @Schema(description = "Date the failure occurred or was noticed")
        LocalDate failureDate,

        @Schema(description = "Full replacement of photo evidence URLs; unchanged when null")
        List<@Size(max = 2048) String> photoEvidenceUrls) {}
