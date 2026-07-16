package com.positivity.warranty.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Records that the back office submitted the claim's reimbursement request to the vendor
 * (PRD §3.7): {@code NOT_SUBMITTED → SUBMITTED} on the claim's single vendor reimbursement.
 */
@Schema(description = "Vendor reimbursement submission (NOT_SUBMITTED -> SUBMITTED)")
public record ReimbursementSubmitRequest(
        @Schema(description = "Amount requested from the vendor") @NotNull @Positive
        BigDecimal amountRequested,

        @Schema(description = "The vendor's own claim reference number, if already assigned") @Size(max = 100)
        String vendorClaimReference,

        @Schema(description = "Back-office notes") @Size(max = 10_000)
        String notes) {}
