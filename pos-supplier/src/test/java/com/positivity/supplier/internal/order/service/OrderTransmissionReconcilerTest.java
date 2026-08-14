package com.positivity.supplier.internal.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import com.positivity.supplier.internal.repository.SupplierTransmissionIntentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Ambiguous-outcome reconciliation (ADR-0052 §3/§4, #1226)")
class OrderTransmissionReconcilerTest {

    private static final UUID INTENT_ID = UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-3e4f5a6b7c8d");
    private static final String DOCUMENT_ID = "DUR0198F3A24C7E7A1B9C2D3E4F5A6B7C8D";

    @Mock
    private SupplierTransmissionIntentRepository intentRepository;

    @Mock
    private OrderStatusQueryRunner statusQueryRunner;

    @Mock
    private TransmissionStateWriter stateWriter;

    private OrderTransmissionReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new OrderTransmissionReconciler(intentRepository, statusQueryRunner, stateWriter);
        when(intentRepository.findById(INTENT_ID)).thenReturn(Optional.of(intent()));
        when(statusQueryRunner.isStatusConfigured(any())).thenReturn(true);
    }

    private static SupplierTransmissionIntentEntity intent() {
        return SupplierTransmissionIntentEntity.builder()
                .transmissionIntentId(INTENT_ID)
                .vendorProfileId(UUID.randomUUID())
                .supplierRef("michelin-eu")
                .purchaseOrderId(UUID.randomUUID())
                .intentType(SupplierPurchaseOrder.IntentType.INITIAL)
                .documentId(DOCUMENT_ID)
                .attemptState(TransmissionAttemptState.DISPATCHING)
                .build();
    }

    private static SupplierOrderStatusResult status(SupplierOrderStatusResult.Status state) {
        return new SupplierOrderStatusResult(DOCUMENT_ID, state, "MICH-1", null, null, null, List.of());
    }

    @Test
    void escalatesImmediatelyWhenTheProfileHasNoStatusBinding() {
        // ADR-0052 §4 is categorical: with nothing to ask, there is nothing to conclude, so every
        // post-send ambiguity goes straight to a human. Not "retried more carefully".
        when(statusQueryRunner.isStatusConfigured(any())).thenReturn(false);

        assertThat(reconciler.reconcile(INTENT_ID, "timeout after send")).isFalse();

        verify(statusQueryRunner, never()).query(any());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(stateWriter).recordManualReview(eq(INTENT_ID), detail.capture());
        assertThat(detail.getValue()).contains("ORDER_STATUS");
    }

    @Test
    void escalatesWhenTheVendorReportsNoSuchOrder() {
        // The body may have been transmitted and the vendor's indexing may simply lag, so NOT_FOUND
        // after DISPATCHING is grounds for a human, never for a re-send.
        when(statusQueryRunner.query(any()))
                .thenReturn(OrderStatusQueryRunner.Answer.of(status(SupplierOrderStatusResult.Status.NOT_FOUND)));

        assertThat(reconciler.reconcile(INTENT_ID, "timeout after send")).isFalse();

        verify(stateWriter).recordManualReview(eq(INTENT_ID), anyString());
        verify(stateWriter, never()).recordReconciliation(any(), any());
    }

    @Test
    void appliesTheVendorsStateWhenItHoldsTheOrder() {
        when(statusQueryRunner.query(any()))
                .thenReturn(OrderStatusQueryRunner.Answer.of(status(SupplierOrderStatusResult.Status.CONFIRMED)));

        assertThat(reconciler.reconcile(INTENT_ID, "timeout after send")).isTrue();

        verify(stateWriter).recordReconciliation(eq(INTENT_ID), any());
        verify(stateWriter, never()).recordManualReview(any(), anyString());
    }

    @Test
    void appliesARejectionTheVendorAlreadyMade() {
        when(statusQueryRunner.query(any()))
                .thenReturn(OrderStatusQueryRunner.Answer.of(status(SupplierOrderStatusResult.Status.REJECTED)));

        assertThat(reconciler.reconcile(INTENT_ID, "timeout after send")).isTrue();

        verify(stateWriter).recordReconciliation(eq(INTENT_ID), any());
    }

    @Test
    void concludesNothingWhenTheVendorCannotBeReached() {
        // "We could not ask" is not "the vendor has no such order". The intent stays where it is so
        // recovery tries again, and the ageing check escalates it if the vendor stays unreachable.
        when(statusQueryRunner.query(any()))
                .thenReturn(OrderStatusQueryRunner.Answer.unavailable("vendor exchange failed"));

        assertThat(reconciler.reconcile(INTENT_ID, "timeout after send")).isFalse();

        verify(stateWriter, never()).recordManualReview(any(), anyString());
        verify(stateWriter, never()).recordReconciliation(any(), any());
        verify(stateWriter, never()).recordPreSendFailure(any(), anyString());
    }

    @Test
    void neverReDispatchesUnderAnyAnswer() {
        // There is no code path from reconciliation back to dispatch, and this asserts the absence:
        // no answer, in any combination, returns an intent to PENDING.
        for (SupplierOrderStatusResult.Status state : SupplierOrderStatusResult.Status.values()) {
            when(statusQueryRunner.query(any())).thenReturn(OrderStatusQueryRunner.Answer.of(status(state)));
            reconciler.reconcile(INTENT_ID, "timeout after send");
        }

        verify(stateWriter, never()).recordPreSendFailure(any(), anyString());
    }
}
