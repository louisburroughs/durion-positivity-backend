package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.enums.BankRecClosePolicy;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * The bank reconciliation close-readiness read model of a period (SPEC-manual-bank-reconciliation §5.3; story S6,
 * #2305): derived on every read, never persisted. {@code ready} is true iff no BLOCKING check remains under the
 * effective policy; under {@code ADVISORY} it reflects only {@code DRAFT_JOURNAL_ENTRIES}.
 */
@Schema(description = "Bank reconciliation close readiness of an accounting period")
public record CloseReadinessResponse(
        @Schema(description = "Period code (YYYY-MM)", example = "2026-08")
        String periodCode,

        @Schema(description = "First day of the period", example = "2026-08-01")
        LocalDate periodStartDate,

        @Schema(description = "Last day of the period", example = "2026-08-31")
        LocalDate periodEndDate,

        @Schema(description = "Period status; OPEN when the period has no row yet", example = "OPEN")
        AccountingPeriodStatus periodStatus,

        @Schema(description = "Effective BANK_REC_CLOSE_POLICY", example = "REQUIRED_WITH_EXCEPTION")
        BankRecClosePolicy policy,

        @Schema(description = "Effective BANK_REC_CLOSE_COVERAGE_LAG_DAYS", example = "0")
        int coverageLagDays,

        @Schema(description = "Whether the period may close under the policy without an exception")
        boolean ready,

        @Schema(description = "BLOCKING checks reported, tenant-wide and per account", example = "1")
        int blockingCount,

        @Schema(description = "WARNING checks reported, tenant-wide and per account", example = "0")
        int warningCount,

        @Schema(description = "Tenant-wide checks (DRAFT_JOURNAL_ENTRIES, CLEARING_BALANCE_AGING)")
        List<CloseReadinessCheck> checks,

        @Schema(description = "One entry per in-scope account, by account code")
        List<CloseReadinessAccount> accounts) {}
