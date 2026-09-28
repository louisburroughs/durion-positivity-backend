package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Start a reconciliation (SPEC-manual-bank-reconciliation §4.1, §6.1; story S4, #2303). Phase 1 takes
 * {@code statementId}: every reconciliation rests on a COMMITTED statement, an interim to a date being a
 * manual-entry statement. The statementless interim body ({@code windowStartDate}, {@code windowEndDate},
 * {@code closingBalance}, {@code openingBalance}) belongs to feed-linked accounts in phase 2 and answers
 * 422 {@code BANK_ACCOUNT_FEED_NOT_LINKED} on every account today.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Start a reconciliation from a COMMITTED bank statement")
public class ReconciliationCreateRequest {

    @NotNull(message = "glAccountId is required")
    @Schema(description = "Reconciled GL cash account id", requiredMode = REQUIRED)
    private UUID glAccountId;

    @NotNull(message = "requestId is required")
    @Schema(
            description = "Caller-generated UUIDv7; a replay with the same payload returns the original",
            requiredMode = REQUIRED)
    private UUID requestId;

    @Schema(description = "The COMMITTED bank statement to reconcile (required in phase 1)")
    private UUID statementId;

    @Schema(description = "Phase 2 statementless interim only — refused in phase 1 (BANK_ACCOUNT_FEED_NOT_LINKED)")
    private LocalDate windowStartDate;

    @Schema(description = "Phase 2 statementless interim only — refused in phase 1 (BANK_ACCOUNT_FEED_NOT_LINKED)")
    private LocalDate windowEndDate;

    @Schema(description = "Phase 2 statementless interim only — refused in phase 1 (BANK_ACCOUNT_FEED_NOT_LINKED)")
    private BigDecimal closingBalance;

    @Schema(description = "Phase 2 statementless interim only — refused in phase 1 (BANK_ACCOUNT_FEED_NOT_LINKED)")
    private BigDecimal openingBalance;
}
