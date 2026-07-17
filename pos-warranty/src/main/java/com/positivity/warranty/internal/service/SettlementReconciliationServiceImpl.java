package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.client.InvoiceClient;
import com.positivity.warranty.internal.dto.SettlementReconciliationRow;
import com.positivity.warranty.internal.entity.ClaimSettlement;
import com.positivity.warranty.internal.enums.ReconciliationCheckStatus;
import com.positivity.warranty.internal.enums.SettlementStatus;
import com.positivity.warranty.internal.enums.SettlementType;
import com.positivity.warranty.internal.repository.ClaimSettlementRepository;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks every FAILED adjustment/refund settlement against pos-invoice (issue #922 item 2):
 * adjustments via the invoice details response (each entry exposes its
 * {@code externalReference}), refunds via {@code GET /v1/invoices/{invoiceId}/refunds}. A
 * per-invoice pos-invoice failure degrades that settlement's row to {@code UNKNOWN} instead of
 * failing the whole worklist (mirrors the client's log-and-degrade read style).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementReconciliationServiceImpl implements SettlementReconciliationService {

    /** Settlement types executed as pos-invoice adjustments ({@code executeInvoiceAdjustment}). */
    private static final Set<SettlementType> ADJUSTMENT_TYPES =
            EnumSet.of(SettlementType.INVOICE_CREDIT, SettlementType.PRORATED_CREDIT, SettlementType.GOODWILL);

    /** All settlement types that write money through pos-invoice. */
    private static final Set<SettlementType> INVOICE_WRITING_TYPES = EnumSet.of(
            SettlementType.INVOICE_CREDIT,
            SettlementType.PRORATED_CREDIT,
            SettlementType.GOODWILL,
            SettlementType.REFUND);

    private static final String REFUND_STATUS_FAILED = "FAILED";

    private final ClaimSettlementRepository settlementRepository;
    private final InvoiceClient invoiceClient;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<SettlementReconciliationRow> reconcileFailedSettlements() {
        List<ClaimSettlement> failed = settlementRepository.findByStatusAndSettlementTypeInOrderByCreatedAtAsc(
                SettlementStatus.FAILED, INVOICE_WRITING_TYPES);
        // Several settlements can point at the same invoice — consult pos-invoice once per
        // invoice per kind within a single worklist build.
        Map<UUID, Optional<List<InvoiceClient.AdjustmentEntry>>> adjustmentsByInvoice = new HashMap<>();
        Map<UUID, Optional<List<InvoiceClient.RefundEntry>>> refundsByInvoice = new HashMap<>();
        return failed.stream()
                .map(settlement -> reconcile(settlement, adjustmentsByInvoice, refundsByInvoice))
                .toList();
    }

    @NonNull
    private SettlementReconciliationRow reconcile(
            @NonNull ClaimSettlement settlement,
            @NonNull Map<UUID, Optional<List<InvoiceClient.AdjustmentEntry>>> adjustmentsByInvoice,
            @NonNull Map<UUID, Optional<List<InvoiceClient.RefundEntry>>> refundsByInvoice) {
        String externalReference = settlement.getId().toString();
        UUID invoiceId = settlement.getInvoiceId();
        if (invoiceId == null) {
            // Defensive: the write helpers set invoiceId before calling pos-invoice, so a
            // FAILED row without one never reached pos-invoice — nothing can be committed.
            return row(settlement, externalReference, ReconciliationCheckStatus.CONFIRMED_ABSENT, null, null);
        }
        if (ADJUSTMENT_TYPES.contains(settlement.getSettlementType())) {
            Optional<List<InvoiceClient.AdjustmentEntry>> adjustments =
                    adjustmentsByInvoice.computeIfAbsent(invoiceId, invoiceClient::getInvoiceAdjustments);
            if (adjustments.isEmpty()) {
                return row(settlement, externalReference, ReconciliationCheckStatus.UNKNOWN, null, null);
            }
            Optional<UUID> matched = adjustments.get().stream()
                    .filter(entry -> externalReference.equals(entry.externalReference()))
                    .map(InvoiceClient.AdjustmentEntry::id)
                    .filter(Objects::nonNull)
                    .findFirst();
            return matched.map(adjustmentId -> row(
                            settlement,
                            externalReference,
                            ReconciliationCheckStatus.CONFIRMED_COMMITTED,
                            adjustmentId,
                            null))
                    .orElseGet(() ->
                            row(settlement, externalReference, ReconciliationCheckStatus.CONFIRMED_ABSENT, null, null));
        }
        Optional<List<InvoiceClient.RefundEntry>> refunds =
                refundsByInvoice.computeIfAbsent(invoiceId, invoiceClient::getInvoiceRefunds);
        if (refunds.isEmpty()) {
            return row(settlement, externalReference, ReconciliationCheckStatus.UNKNOWN, null, null);
        }
        // A FAILED refund record means the gateway declined — the customer was NOT paid, so it
        // does not count as committed money.
        Optional<UUID> matched = refunds.get().stream()
                .filter(entry -> externalReference.equals(entry.externalReference()))
                .filter(entry -> !REFUND_STATUS_FAILED.equals(entry.status()))
                .map(InvoiceClient.RefundEntry::id)
                .filter(Objects::nonNull)
                .findFirst();
        return matched.map(refundId -> row(
                        settlement, externalReference, ReconciliationCheckStatus.CONFIRMED_COMMITTED, null, refundId))
                .orElseGet(() ->
                        row(settlement, externalReference, ReconciliationCheckStatus.CONFIRMED_ABSENT, null, null));
    }

    @NonNull
    private static SettlementReconciliationRow row(
            @NonNull ClaimSettlement settlement,
            @NonNull String externalReference,
            @NonNull ReconciliationCheckStatus checkStatus,
            UUID matchedAdjustmentId,
            UUID matchedRefundId) {
        Boolean committed =
                switch (checkStatus) {
                    case CONFIRMED_COMMITTED -> Boolean.TRUE;
                    case CONFIRMED_ABSENT -> Boolean.FALSE;
                    case UNKNOWN -> null;
                };
        return new SettlementReconciliationRow(
                settlement.getId(),
                settlement.getClaimId(),
                settlement.getSettlementType(),
                settlement.getStatus(),
                settlement.getInvoiceId(),
                externalReference,
                committed,
                matchedAdjustmentId,
                matchedRefundId,
                checkStatus,
                settlement.getCoveredAmount(),
                settlement.getCustomerAmount(),
                settlement.getCreatedAt(),
                settlement.getUpdatedAt());
    }
}
