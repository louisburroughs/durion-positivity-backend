package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Optional body of {@code POST /v1/accounting/periods/{periodCode}/close} (SPEC-manual-bank-reconciliation §5.9;
 * story S6, #2305).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Optional close request carrying a bank reconciliation exception")
public class PeriodCloseRequest {

    @Schema(
            description = "Close despite BLOCKING bank reconciliation checks. Honoured under"
                    + " REQUIRED_WITH_EXCEPTION for a caller holding accounting:period:close and"
                    + " accounting:period:override (403 PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED otherwise); refused"
                    + " with 422 PERIOD_BANK_RECONCILIATION_INCOMPLETE under REQUIRED; ignored under ADVISORY and"
                    + " when readiness holds",
            nullable = true)
    private BankReconciliationExceptionRequest bankReconciliationException;
}
