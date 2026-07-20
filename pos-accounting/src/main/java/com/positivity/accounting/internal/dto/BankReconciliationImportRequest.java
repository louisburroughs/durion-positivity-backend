package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload to import a bank statement CSV and start a reconciliation
 * (Story F2, issue #965). The CSV columns are {@code date, description, amount
 * [signed], reference}; {@link #csv} may be raw text lines or base64-encoded text.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to import a bank statement CSV and start a reconciliation")
public class BankReconciliationImportRequest {

    @NotNull(message = "glAccountId is required")
    @Schema(description = "Reconcilable GL cash account being reconciled", requiredMode = REQUIRED)
    private UUID glAccountId;

    @NotNull(message = "periodStartDate is required")
    @Schema(description = "Statement period start date", example = "2026-06-01", requiredMode = REQUIRED)
    private LocalDate periodStartDate;

    @NotNull(message = "periodEndDate is required")
    @Schema(description = "Statement period end date", example = "2026-06-30", requiredMode = REQUIRED)
    private LocalDate periodEndDate;

    @NotNull(message = "statementDate is required")
    @Schema(
            description = "Statement date; GL ending balance is computed as-of this date",
            example = "2026-06-30",
            requiredMode = REQUIRED)
    private LocalDate statementDate;

    @NotNull(message = "statementEndingBalance is required")
    @Schema(description = "Statement ending balance", example = "12500.0000", requiredMode = REQUIRED)
    private BigDecimal statementEndingBalance;

    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
    @Schema(
            description = "ISO currency code; single currency governs the reconciliation",
            example = "USD",
            requiredMode = REQUIRED)
    private String currency;

    @NotBlank(message = "csv is required")
    @Schema(
            description =
                    "Bank statement CSV (columns: date, description, amount, reference), raw text lines or base64",
            requiredMode = REQUIRED)
    private String csv;
}
