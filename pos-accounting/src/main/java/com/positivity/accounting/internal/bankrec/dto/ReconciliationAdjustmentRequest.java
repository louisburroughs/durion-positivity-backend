package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
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
 * Post a reconciliation adjustment (Story F2 #965, decision D-6; extended by S4 #2303 — SPEC §3.5, §4.7). It
 * posts a real balanced journal entry. An {@code OTHER} adjustment names exactly one link — a bank transaction,
 * a match residual ({@code settlesMatchId}) or this statement's gap ({@code bridgesStatementId}) — and a
 * justification; a residual or bridge takes its amount from the server. A {@code TRANSFER} names its counter
 * bank account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Post a reconciliation adjustment (a real journal entry)")
public class ReconciliationAdjustmentRequest {

    @NotNull(message = "type is required")
    @Schema(description = "Adjustment type", example = "BANK_FEE", requiredMode = REQUIRED)
    private BankAdjustmentType type;

    @Digits(integer = 15, fraction = 4, message = "amount must have at most 15 integer and 4 fractional digits")
    @Schema(
            description = "Signed amount; positive increases the reconciled cash. Required unless settlesMatchId or"
                    + " bridgesStatementId is set, when the server computes it and a sent value must equal it",
            example = "-12.5000")
    private BigDecimal amount;

    @Size(max = 500, message = "description must not exceed 500 characters")
    @Schema(description = "Description recorded on the adjustment", example = "Monthly account service charge")
    private String description;

    @NotNull(message = "requestId is required")
    @Schema(
            description = "Caller-generated UUIDv7; a replay returns the original with replayed true",
            requiredMode = REQUIRED)
    private UUID requestId;

    @Schema(description = "The UNMATCHED bank transaction this adjustment explains; its cash line is matched to it")
    private UUID bankTransactionId;

    @Schema(description = "OTHER only: the ACCEPTED match whose residual this settles")
    private UUID settlesMatchId;

    @Schema(description = "OTHER only: this reconciliation's statement, whose acknowledged gap this bridges")
    private UUID bridgesStatementId;

    @Size(max = 1000, message = "justification must not exceed 1000 characters")
    @Schema(description = "Required for OTHER (at least 10 characters)", example = "Bank debit pending classification")
    private String justification;

    @Schema(
            description = "The date to post at when the explaining date's period is closed; it must be in an OPEN"
                    + " period",
            example = "2026-10-01")
    private LocalDate transactionDate;

    @Size(max = 1000, message = "overrideJustification must not exceed 1000 characters")
    @Schema(description = "Post into a CLOSED period; needs accounting:period:override (never a hard-locked one)")
    private String overrideJustification;

    @Schema(description = "TRANSFER only: the counter bank account (a reconcilable BANK_CASH account)")
    private UUID counterGlAccountId;
}
