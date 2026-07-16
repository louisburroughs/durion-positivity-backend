package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.RegistrationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/** Create/update payload for sold/instantiated coverage (PRD §3.3). */
@Schema(description = "Warranty registration create/update request — sold/instantiated coverage")
public record RegistrationRequest(
        @Schema(description = "Governing warranty policy id") @NotNull
        UUID policyId,

        @Schema(description = "pos-customer customer id") @NotNull
        UUID customerId,

        @Schema(description = "pos-vehicle-inventory vehicle id, when vehicle-bound")
        UUID vehicleId,

        @Schema(description = "Originating pos-invoice invoice id")
        UUID sourceInvoiceId,

        @Schema(description = "Originating pos-invoice invoice line id")
        UUID sourceInvoiceLineId,

        @Schema(description = "Provider contract number", example = "RH-2026-88412") @Size(max = 100)
        String contractNumber,

        @Schema(description = "Date the coverage was purchased") @NotNull
        LocalDate purchaseDate,

        @Schema(description = "Coverage end date, when bounded")
        LocalDate expiresAt,

        @Schema(description = "Lifecycle status; defaults to ACTIVE on create")
        RegistrationStatus status) {}
