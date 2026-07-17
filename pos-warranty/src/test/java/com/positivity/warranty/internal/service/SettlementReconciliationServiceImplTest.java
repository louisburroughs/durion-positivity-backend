package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.warranty.internal.client.InvoiceClient;
import com.positivity.warranty.internal.dto.SettlementReconciliationRow;
import com.positivity.warranty.internal.entity.ClaimSettlement;
import com.positivity.warranty.internal.enums.ReconciliationCheckStatus;
import com.positivity.warranty.internal.enums.SettlementStatus;
import com.positivity.warranty.internal.enums.SettlementType;
import com.positivity.warranty.internal.repository.ClaimSettlementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Settlement reconciliation worklist (issue #922 item 2): a FAILED settlement is checked
 * against pos-invoice by externalReference (the settlement id) — committed writes surface as
 * the double-pay-risk rows, absent writes as safely-retryable, and a pos-invoice read failure
 * degrades that row to UNKNOWN instead of failing the whole worklist.
 */
@ExtendWith(MockitoExtension.class)
class SettlementReconciliationServiceImplTest {

    private static final UUID CLAIM_ID = UUID.fromString("018f0000-0000-7000-8000-000000000601");
    private static final UUID INVOICE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000602");
    private static final UUID SETTLEMENT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000603");
    private static final UUID OTHER_SETTLEMENT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000604");
    private static final UUID ADJUSTMENT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000605");
    private static final UUID REFUND_ID = UUID.fromString("018f0000-0000-7000-8000-000000000606");
    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final BigDecimal COVERED = new BigDecimal("80.0000");

    @Mock
    private ClaimSettlementRepository settlementRepository;

    @Mock
    private InvoiceClient invoiceClient;

    @InjectMocks
    private SettlementReconciliationServiceImpl service;

    private static ClaimSettlement failedSettlement(UUID id, SettlementType type) {
        return ClaimSettlement.builder()
                .id(id)
                .claimId(CLAIM_ID)
                .settlementType(type)
                .status(SettlementStatus.FAILED)
                .invoiceId(INVOICE_ID)
                .coveredAmount(COVERED)
                .customerAmount(new BigDecimal("20.0000"))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private void stubFailed(ClaimSettlement... settlements) {
        when(settlementRepository.findByStatusAndSettlementTypeInOrderByCreatedAtAsc(
                        ArgumentMatchers.eq(SettlementStatus.FAILED),
                        ArgumentMatchers.<Collection<SettlementType>>any()))
                .thenReturn(List.of(settlements));
    }

    private static InvoiceClient.AdjustmentEntry adjustment(UUID id, String externalReference) {
        return new InvoiceClient.AdjustmentEntry(id, "WARRANTY", COVERED, externalReference, NOW);
    }

    private static InvoiceClient.RefundEntry refund(UUID id, String externalReference, String status) {
        return new InvoiceClient.RefundEntry(id, null, COVERED, status, "OTHER", externalReference, "gw-1", NOW, NOW);
    }

    @Test
    void committedAdjustmentIsTheDoublePayRiskRow() {
        stubFailed(failedSettlement(SETTLEMENT_ID, SettlementType.INVOICE_CREDIT));
        when(invoiceClient.getInvoiceAdjustments(INVOICE_ID))
                .thenReturn(Optional.of(List.of(
                        adjustment(UUID.randomUUID(), "unrelated-ref"),
                        adjustment(ADJUSTMENT_ID, SETTLEMENT_ID.toString()))));

        List<SettlementReconciliationRow> rows = service.reconcileFailedSettlements();

        assertThat(rows).hasSize(1);
        SettlementReconciliationRow row = rows.getFirst();
        assertThat(row.settlementId()).isEqualTo(SETTLEMENT_ID);
        assertThat(row.claimId()).isEqualTo(CLAIM_ID);
        assertThat(row.settlementType()).isEqualTo(SettlementType.INVOICE_CREDIT);
        assertThat(row.settlementStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(row.invoiceId()).isEqualTo(INVOICE_ID);
        assertThat(row.externalReference()).isEqualTo(SETTLEMENT_ID.toString());
        assertThat(row.committedOnInvoice()).isTrue();
        assertThat(row.matchedAdjustmentId()).isEqualTo(ADJUSTMENT_ID);
        assertThat(row.matchedRefundId()).isNull();
        assertThat(row.checkStatus()).isEqualTo(ReconciliationCheckStatus.CONFIRMED_COMMITTED);
        assertThat(row.coveredAmount()).isEqualByComparingTo(COVERED);
        assertThat(row.createdAt()).isEqualTo(NOW);
    }

    @Test
    void absentAdjustmentIsSafelyRetryable() {
        stubFailed(failedSettlement(SETTLEMENT_ID, SettlementType.GOODWILL));
        when(invoiceClient.getInvoiceAdjustments(INVOICE_ID))
                .thenReturn(Optional.of(List.of(adjustment(UUID.randomUUID(), "some-other-settlement"))));

        List<SettlementReconciliationRow> rows = service.reconcileFailedSettlements();

        assertThat(rows.getFirst().checkStatus()).isEqualTo(ReconciliationCheckStatus.CONFIRMED_ABSENT);
        assertThat(rows.getFirst().committedOnInvoice()).isFalse();
        assertThat(rows.getFirst().matchedAdjustmentId()).isNull();
    }

    @Test
    void adjustmentTransportFailureDegradesToUnknownWithoutFailingTheWorklist() {
        stubFailed(
                failedSettlement(SETTLEMENT_ID, SettlementType.PRORATED_CREDIT),
                failedSettlement(OTHER_SETTLEMENT_ID, SettlementType.REFUND));
        when(invoiceClient.getInvoiceAdjustments(INVOICE_ID)).thenReturn(Optional.empty());
        when(invoiceClient.getInvoiceRefunds(INVOICE_ID))
                .thenReturn(Optional.of(List.of(refund(REFUND_ID, OTHER_SETTLEMENT_ID.toString(), "COMPLETED"))));

        List<SettlementReconciliationRow> rows = service.reconcileFailedSettlements();

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().checkStatus()).isEqualTo(ReconciliationCheckStatus.UNKNOWN);
        assertThat(rows.getFirst().committedOnInvoice()).isNull();
        // The refund settlement is still fully reconciled despite the adjustment-read failure.
        assertThat(rows.getLast().checkStatus()).isEqualTo(ReconciliationCheckStatus.CONFIRMED_COMMITTED);
        assertThat(rows.getLast().matchedRefundId()).isEqualTo(REFUND_ID);
    }

    @Test
    void committedRefundMatchesByExternalReference() {
        stubFailed(failedSettlement(SETTLEMENT_ID, SettlementType.REFUND));
        when(invoiceClient.getInvoiceRefunds(INVOICE_ID))
                .thenReturn(Optional.of(List.of(
                        refund(UUID.randomUUID(), "unrelated-ref", "COMPLETED"),
                        refund(REFUND_ID, SETTLEMENT_ID.toString(), "COMPLETED"))));

        List<SettlementReconciliationRow> rows = service.reconcileFailedSettlements();

        assertThat(rows.getFirst().checkStatus()).isEqualTo(ReconciliationCheckStatus.CONFIRMED_COMMITTED);
        assertThat(rows.getFirst().committedOnInvoice()).isTrue();
        assertThat(rows.getFirst().matchedRefundId()).isEqualTo(REFUND_ID);
        assertThat(rows.getFirst().matchedAdjustmentId()).isNull();
    }

    @Test
    void failedRefundRecordDoesNotCountAsCommittedMoney() {
        stubFailed(failedSettlement(SETTLEMENT_ID, SettlementType.REFUND));
        when(invoiceClient.getInvoiceRefunds(INVOICE_ID))
                .thenReturn(Optional.of(List.of(refund(REFUND_ID, SETTLEMENT_ID.toString(), "FAILED"))));

        List<SettlementReconciliationRow> rows = service.reconcileFailedSettlements();

        assertThat(rows.getFirst().checkStatus()).isEqualTo(ReconciliationCheckStatus.CONFIRMED_ABSENT);
        assertThat(rows.getFirst().committedOnInvoice()).isFalse();
    }

    @Test
    void refundTransportFailureDegradesToUnknown() {
        stubFailed(failedSettlement(SETTLEMENT_ID, SettlementType.REFUND));
        when(invoiceClient.getInvoiceRefunds(INVOICE_ID)).thenReturn(Optional.empty());

        List<SettlementReconciliationRow> rows = service.reconcileFailedSettlements();

        assertThat(rows.getFirst().checkStatus()).isEqualTo(ReconciliationCheckStatus.UNKNOWN);
        assertThat(rows.getFirst().committedOnInvoice()).isNull();
    }

    @Test
    void settlementWithoutInvoiceIdNeverReachedPosInvoice() {
        ClaimSettlement noInvoice = failedSettlement(SETTLEMENT_ID, SettlementType.INVOICE_CREDIT);
        noInvoice.setInvoiceId(null);
        stubFailed(noInvoice);

        List<SettlementReconciliationRow> rows = service.reconcileFailedSettlements();

        assertThat(rows.getFirst().checkStatus()).isEqualTo(ReconciliationCheckStatus.CONFIRMED_ABSENT);
        assertThat(rows.getFirst().invoiceId()).isNull();
    }

    @Test
    void consultsPosInvoiceOncePerInvoicePerKind() {
        stubFailed(
                failedSettlement(SETTLEMENT_ID, SettlementType.INVOICE_CREDIT),
                failedSettlement(OTHER_SETTLEMENT_ID, SettlementType.GOODWILL));
        when(invoiceClient.getInvoiceAdjustments(INVOICE_ID)).thenReturn(Optional.of(List.of()));

        service.reconcileFailedSettlements();

        verify(invoiceClient).getInvoiceAdjustments(INVOICE_ID);
    }

    @Test
    void emptyWorklistWhenNoFailedSettlements() {
        stubFailed();

        assertThat(service.reconcileFailedSettlements()).isEmpty();
    }
}
