package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.ClaimType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Draft-claim intake payload (PRD §7 step 1-3, §8). Customer and vehicle are required; VIN and
 * odometer are read from pos-vehicle-inventory and frozen onto the claim server-side. Origin
 * fields are nullable for walk-ins with no locatable sale — the claim is then flagged
 * {@code originUnverified}.
 */
@Schema(description = "Create a DRAFT warranty claim")
public record ClaimCreateRequest(
        @Schema(description = "Claim type", example = "MANUFACTURER_DEFECT") @NotNull
        ClaimType claimType,

        @Schema(description = "pos-customer customer id") @NotNull
        UUID customerId,

        @Schema(description = "pos-vehicle-inventory vehicle id; VIN + odometer are snapshotted from it") @NotNull
        UUID vehicleId,

        @Schema(description = "Location where the claim is taken")
        UUID locationId,

        @Schema(description = "Originating workorder, when the sale was located")
        UUID originWorkorderId,

        @Schema(description = "Originating invoice, when the sale was located")
        UUID originInvoiceId,

        @Schema(description = "Original sale date (manual entry allowed for walk-ins)")
        LocalDate originSaleDate,

        @Schema(description = "Registration id for registration-bound coverage, when already known")
        UUID registrationId,

        @Schema(description = "What failed and how") @Size(max = 10_000)
        String failureDescription,

        @Schema(description = "Date the failure occurred or was noticed")
        LocalDate failureDate,

        @Schema(description = "Photo evidence URLs (URL-reference pattern)")
        List<@Size(max = 2048) String> photoEvidenceUrls,

        @Schema(description = "Initial claim lines (may also be added later while DRAFT/INFO_NEEDED)")
        List<@Valid ClaimLineRequest> lines) {}
