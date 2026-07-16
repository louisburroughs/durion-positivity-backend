package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.RegistrationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** API representation of sold/instantiated coverage (PRD §3.3). */
@Schema(description = "Warranty registration — sold/instantiated coverage")
public record RegistrationResponse(
        UUID id,
        UUID policyId,
        UUID customerId,
        UUID vehicleId,
        UUID sourceInvoiceId,
        UUID sourceInvoiceLineId,
        String contractNumber,
        LocalDate purchaseDate,
        LocalDate expiresAt,
        RegistrationStatus status,
        Instant createdAt,
        Instant updatedAt,
        Long version) {}
