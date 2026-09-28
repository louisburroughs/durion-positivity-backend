package com.positivity.accounting.internal.bankrec.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A bank-account profile as stored (SPEC §3.1, D21; story S2, #2301). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Bank-account profile of a reconcilable BANK_CASH GL account")
public class BankAccountProfileResponse {

    @Schema(description = "GL account id")
    private UUID glAccountId;

    @Schema(description = "GL account code", example = "1000")
    private String accountCode;

    @Schema(description = "GL account name", example = "Cash")
    private String accountName;

    @Schema(description = "Bank name", example = "First National")
    private String bankName;

    @Schema(description = "Last digits of the account number", example = "4321")
    private String accountMask;

    @Schema(description = "ISO 4217 code", example = "USD")
    private String currency;

    @Schema(description = "Default column mapping for file imports")
    private Map<String, Object> defaultColumnMapping;

    @Schema(description = "Statement cycle hint", example = "MONTHLY_EOM")
    private String statementCycleHint;

    @Schema(description = "Reconciliation baseline (read-only; moved only by an acknowledged statement)")
    private LocalDate reconciliationBaselineDate;

    @Schema(description = "When the profile was last changed")
    private Instant updatedAt;
}
