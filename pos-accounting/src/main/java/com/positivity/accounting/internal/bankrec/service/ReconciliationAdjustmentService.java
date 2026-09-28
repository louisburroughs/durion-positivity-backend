package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.AdjustmentReverseRequest;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationAdjustmentResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAdjustmentRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Reconciliation adjustments (SPEC-manual-bank-reconciliation §3.5, §4.2, §4.6, §4.7, §4.9 path 2; story S4,
 * #2303): the only reconciliation artefact that posts — typed, {@code TRANSFER}, or a linked, justified,
 * threshold-checked {@code OTHER} (bank transaction, residual settlement, gap bridge) — and its explicit
 * reversal.
 */
public interface ReconciliationAdjustmentService {

    /** Post an adjustment; a replayed requestId returns the original with {@code replayed} true. */
    @NonNull
    BankReconciliationAdjustmentResponse addAdjustment(
            @NonNull UUID reconciliationId, @NonNull ReconciliationAdjustmentRequest request);

    /** Reverse a posted adjustment through {@code JournalEntryService.reverseJournalEntry}. */
    @NonNull
    BankReconciliationAdjustmentResponse reverse(
            @NonNull UUID reconciliationId, @NonNull UUID adjustmentId, @NonNull AdjustmentReverseRequest request);
}
