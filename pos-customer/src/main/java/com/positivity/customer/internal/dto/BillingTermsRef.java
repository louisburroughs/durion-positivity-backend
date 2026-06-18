package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reference entry for a billing term option.
 *
 * <p>
 * Used in the {@code GET /v1/crm/billing-terms} response to populate
 * billing-term dropdowns on the frontend without hardcoding values.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Billing term reference entry")
public class BillingTermsRef {

    @NotBlank
    @Schema(
            description = "Billing term code identifier",
            example = "NET_30",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @NotBlank
    @Schema(description = "Human-readable label", example = "Net 30", requiredMode = Schema.RequiredMode.REQUIRED)
    private String label;

    @Schema(
            description = "Net payment days. Negative values indicate prepayment is required.",
            example = "30",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private int netDays;
}
