package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Request DTO for reversing a payment application.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114 - Reversibility</a>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Schema(description = "Request payload for reversing a payment application")
public class PaymentApplicationReversalRequest {

    /**
     * Reason for reversal (required for audit trail).
     */
    @Schema(
            description = "Reason for the reversal, recorded for the audit trail",
            example = "Payment applied to the wrong invoice and must be reallocated",
            requiredMode = REQUIRED)
    @NotBlank(message = "Reversal reason is required")
    @Size(min = 10, max = 1000, message = "Reversal reason must be between 10 and 1000 characters")
    private String reason;
}
