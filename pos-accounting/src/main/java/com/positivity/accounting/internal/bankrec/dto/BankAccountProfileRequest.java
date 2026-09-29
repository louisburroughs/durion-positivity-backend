package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The thin bank-account profile a preparer maintains (SPEC §3.1, §6.1, D21; story S2, #2301). There
 * is no baseline field: the reconciliation baseline moves only with an acknowledged statement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Bank-account profile: bank name, mask, currency, default column mapping and cycle hint")
public class BankAccountProfileRequest {

    @Schema(description = "Bank name (at most 100 characters)", example = "First National")
    private String bankName;

    @Schema(description = "Last digits of the account number (at most 8 characters)", example = "4321")
    private String accountMask;

    @Schema(description = "ISO 4217 code; must be the ledger currency", example = "USD", requiredMode = REQUIRED)
    private String currency;

    @Schema(description = "The column mapping the account's next file import defaults to")
    private Map<String, Object> defaultColumnMapping;

    @Schema(description = "How the bank cycles statements (at most 32 characters)", example = "MONTHLY_EOM")
    private String statementCycleHint;
}
