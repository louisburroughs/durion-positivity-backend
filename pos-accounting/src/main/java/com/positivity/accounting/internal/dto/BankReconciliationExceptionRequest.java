package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A bank reconciliation close exception (SPEC-manual-bank-reconciliation §5.2, I5, D4, D15; story S6, #2305).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Exception to close a period whose bank accounts are not reconciled")
public class BankReconciliationExceptionRequest {

    @NotBlank(message = "justification is required")
    @Size(max = 1000, message = "justification must not exceed 1000 characters")
    @Schema(
            description = "Why the period closes without bank reconciliation readiness; at least 10 characters"
                    + " (400 JUSTIFICATION_REQUIRED otherwise); recorded with the readiness snapshot in the"
                    + " PERIOD_CLOSE_BANKREC_EXCEPTION audit row",
            example = "September bank statement delayed by the bank; controller approved close",
            requiredMode = REQUIRED)
    private String justification;
}
