package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.ReconciliationCheckStatus;
import com.positivity.warranty.internal.enums.SettlementStatus;
import com.positivity.warranty.internal.enums.SettlementType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One FAILED settlement checked against pos-invoice (issue #922). Rows with
 * {@code committedOnInvoice == true} are the double-pay-risk worklist: warranty recorded the
 * settlement as FAILED (transport failure) but pos-invoice committed the money write —
 * retrying with a fresh settlement would pay the customer twice.
 */
@Schema(description = "FAILED settlement reconciled against pos-invoice — the double-pay-risk worklist")
public record SettlementReconciliationRow(
        UUID settlementId,
        UUID claimId,
        SettlementType settlementType,

        @Schema(description = "Always FAILED in this worklist")
        SettlementStatus settlementStatus,

        UUID invoiceId,

        @Schema(description = "Idempotency key warranty sent to pos-invoice (the settlement id)")
        String externalReference,

        @Schema(description = "Whether pos-invoice committed the write; null when checkStatus is UNKNOWN")
        Boolean committedOnInvoice,

        UUID matchedAdjustmentId,
        UUID matchedRefundId,
        ReconciliationCheckStatus checkStatus,
        BigDecimal coveredAmount,
        BigDecimal customerAmount,
        Instant createdAt,
        Instant updatedAt) {}
