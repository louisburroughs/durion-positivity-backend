package com.positivity.accounting.internal.dto;

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
public class PaymentApplicationReversalRequest {

    /**
     * Reason for reversal (required for audit trail).
     */
    @NotBlank(message = "Reversal reason is required")
    @Size(min = 10, max = 1000, message = "Reversal reason must be between 10 and 1000 characters")
    private String reason;
}
