package com.positivity.accounting.service;

import com.positivity.accounting.internal.entity.ProcessorSettlementLine;
import com.positivity.domainevents.payment.SettlementReportedV1;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Processor settlement reconciliation (story F1c, issue #963, decisions D-9…D-14).
 *
 * <p>Ingests the normalized {@code SettlementReportedV1} contract, matches CHARGE lines to platform
 * {@code ReceivablePayment}s on gross within the provider's tolerance, and enqueues one batched
 * settlement journal entry per settlement. Lines that cannot be matched are left {@code UNMATCHED}
 * for the review workflow (manual match / write-off) — never mis-posted.
 */
public interface SettlementReconciliationService {

    /**
     * Ingest one settlement fact: persist the settlement and its lines, match CHARGE lines to
     * receivable payments, and enqueue the batched GL posting work item (all in the caller's
     * transaction). Idempotent on the settlement's {@code eventId} — a replay is a no-op.
     *
     * @param payload the normalized settlement contract
     */
    void ingestSettlement(@NonNull SettlementReportedV1 payload);

    /** List a settlement's lines, optionally filtered to {@code UNMATCHED} only. */
    List<ProcessorSettlementLine> listLines(@NonNull String settlementId, boolean unmatchedOnly);

    /**
     * Manually match an {@code UNMATCHED} line to an AR receivable payment, posting a reclass entry
     * (Dr Settlement Suspense / Cr Undeposited Funds). AP matching is not supported in v1.
     *
     * @param lineId settlement line id
     * @param receivablePaymentId the receivable payment to match to
     * @return the updated line
     */
    ProcessorSettlementLine manualMatchToReceivable(@NonNull UUID lineId, @NonNull UUID receivablePaymentId);

    /**
     * Write off an {@code UNMATCHED} line below the configured threshold, posting a reversible
     * adjustment entry (Dr Settlement Suspense / Cr Settlement Adjustment). Requires a reason.
     *
     * @param lineId settlement line id
     * @param reason mandatory write-off reason (audited)
     * @return the updated line
     */
    ProcessorSettlementLine writeOffLine(@NonNull UUID lineId, @NonNull String reason);
}
