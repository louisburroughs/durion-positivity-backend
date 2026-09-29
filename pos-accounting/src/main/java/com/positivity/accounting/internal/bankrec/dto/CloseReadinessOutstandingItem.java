package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An {@code OPEN} outstanding item listed by close readiness: an explained timing difference of the book-to-bank
 * bridge (SPEC-manual-bank-reconciliation §5.4, I2; story S6, #2305).
 */
@Schema(description = "An OPEN outstanding item, an explained book-to-bank difference")
public record CloseReadinessOutstandingItem(
        @Schema(description = "Outstanding item id") UUID outstandingItemId,

        @Schema(description = "LEDGER or BANK", example = "LEDGER")
        OutstandingItemSide side,

        @Schema(description = "Item kind", example = "OUTSTANDING_CHECK")
        OutstandingItemKind itemKind,

        @Schema(description = "Item date", example = "2026-08-28")
        LocalDate itemDate,

        @Schema(description = "Signed amount in the functional currency", example = "-120.00")
        BigDecimal signedAmount,

        @Schema(description = "Age in days at the readiness reference date", example = "3")
        long ageDays) {}
