package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.ReimbursementStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Records a vendor decision on a submitted reimbursement (PRD §3.7). Allowed target statuses:
 * {@code APPROVED}, {@code PARTIALLY_APPROVED}, {@code DENIED}, {@code CREDIT_RECEIVED},
 * {@code WRITTEN_OFF}; the legal source states are enforced by the service state machine.
 */
@Schema(description = "Vendor reimbursement lifecycle update (vendor decision / credit / write-off)")
public record ReimbursementUpdateRequest(
        @Schema(
                description = "Target status",
                allowableValues = {"APPROVED", "PARTIALLY_APPROVED", "DENIED", "CREDIT_RECEIVED", "WRITTEN_OFF"})
        @NotNull
        ReimbursementStatus status,

        @Schema(description = "Amount the vendor approved (required for APPROVED / PARTIALLY_APPROVED)") @Positive
        BigDecimal amountApproved,

        @Schema(description = "Vendor credit/payment reference (typically with CREDIT_RECEIVED)") @Size(max = 100)
        String creditReference,

        @Schema(description = "Back-office notes") @Size(max = 10_000)
        String notes) {}
