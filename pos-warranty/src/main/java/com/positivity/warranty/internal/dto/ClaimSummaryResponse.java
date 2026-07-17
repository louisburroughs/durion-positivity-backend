package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.enums.EligibilityResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Paged claim search row (PRD §8 {@code GET /v1/warranty/claims?...}). */
@Schema(description = "Warranty claim summary (search result row)")
public record ClaimSummaryResponse(
        UUID id,
        String claimCode,
        ClaimType claimType,
        ClaimStatus status,
        UUID customerId,
        UUID vehicleId,
        String vin,
        UUID locationId,
        LocalDate failureDate,
        EligibilityResult eligibilityResult,
        Instant createdAt,
        Instant updatedAt) {}
