package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ExtInvoicePaymentReversal;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoicePaymentReversalRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.payment.PaymentReversedV1;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code payment.payment.reversed} intake added to {@link SettlementEventsListener} for issue
 * #1620: completed refunds ({@code reversalType == "REFUND"}) are replicated into {@link
 * ExtInvoicePaymentReversal}; VOID reversals are deliberately not stored. Follows the same
 * "malformed payload skipped-and-marked, business/DB error propagates unmarked" reliability
 * contract proven for {@code payment.payment.settled} by {@link
 * SettlementEventsListenerPaymentSettledTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementEventsListener — payment.payment.reversed intake (issue #1620)")
class SettlementEventsListenerPaymentReversedTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID PAYMENT_INTENT_ID = UUID.fromString("01960003-0000-7000-8000-000000000020");
    private static final UUID REFUND_ID = UUID.fromString("01960003-0000-7000-8000-000000000021");
    private static final UUID INVOICE_ID = UUID.fromString("01960003-0000-7000-8000-000000000022");
    private static final UUID ORDER_ID = UUID.fromString("01960003-0000-7000-8000-000000000023");
    private static final String PARTY_ID = "party-9001";
    private static final String EVENT_ID = "01960003-0000-7000-8000-000000000099";

    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private SettlementReconciliationService reconciliationService;

    @Mock
    private PaymentApplicationService paymentApplicationService;

    @Mock
    private ExtInvoicePaymentReversalRepository extInvoicePaymentReversalRepository;

    @Mock
    private ExtInvoiceDepositCreditApplicationRepository extInvoiceDepositCreditApplicationRepository;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void stubNotProcessed() {
        when(processedEventRepository.existsById(anyString())).thenReturn(false);
        meterRegistry = new SimpleMeterRegistry();
    }

    @SuppressWarnings("unchecked")
    private SettlementEventsListener listener() {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meterRegistry);
        return new SettlementEventsListener(
                CLOCK,
                mapper,
                processedEventRepository,
                reconciliationService,
                paymentApplicationService,
                extInvoicePaymentReversalRepository,
                extInvoiceDepositCreditApplicationRepository,
                provider);
    }

    private double rejectedCount() {
        var counter = meterRegistry.find("replica.payload.rejected").counter();
        return counter == null ? 0d : counter.count();
    }

    private String envelope(String eventId, Object payload) {
        return mapper.writeValueAsString(
                Map.of("eventType", PaymentReversedV1.EVENT_TYPE, "eventId", eventId, "payload", payload));
    }

    private PaymentReversedV1 reversal(String reversalType, UUID paymentIntentId, UUID invoiceId, String partyId) {
        return new PaymentReversedV1(
                paymentIntentId,
                REFUND_ID,
                invoiceId,
                ORDER_ID,
                partyId,
                reversalType,
                new BigDecimal("42.50"),
                "USD",
                "customer_request",
                Instant.parse("2026-08-27T00:00:00Z"));
    }

    @Test
    @DisplayName("a completed REFUND is replicated with every payload field plus sourceEventId, then marked processed")
    void refundIsReplicated() {
        listener().onPaymentEvent(envelope(EVENT_ID, reversal("REFUND", PAYMENT_INTENT_ID, INVOICE_ID, PARTY_ID)));

        ArgumentCaptor<ExtInvoicePaymentReversal> captor = ArgumentCaptor.forClass(ExtInvoicePaymentReversal.class);
        verify(extInvoicePaymentReversalRepository).save(captor.capture());
        ExtInvoicePaymentReversal saved = captor.getValue();
        assertThat(saved.getRefundId()).isEqualTo(REFUND_ID);
        assertThat(saved.getPaymentIntentId()).isEqualTo(PAYMENT_INTENT_ID);
        assertThat(saved.getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(saved.getPartyId()).isEqualTo(PARTY_ID);
        assertThat(saved.getAmount()).isEqualByComparingTo("42.50");
        assertThat(saved.getCurrencyCode()).isEqualTo("USD");
        assertThat(saved.getReversalType()).isEqualTo("REFUND");
        assertThat(saved.getReversedAt()).isEqualTo(Instant.parse("2026-08-27T00:00:00Z"));
        assertThat(saved.getSourceEventId()).isEqualTo(UUID.fromString(EVENT_ID));
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a VOID reversal is not replicated but is still marked processed")
    void voidIsIgnoredButMarkedProcessed() {
        listener().onPaymentEvent(envelope(EVENT_ID, reversal("VOID", PAYMENT_INTENT_ID, INVOICE_ID, PARTY_ID)));

        verify(extInvoicePaymentReversalRepository, never()).save(any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a standalone refund with no paymentIntentId/invoiceId/partyId is still stored")
    void standaloneRefundIsStored() {
        listener().onPaymentEvent(envelope(EVENT_ID, reversal("REFUND", null, null, null)));

        ArgumentCaptor<ExtInvoicePaymentReversal> captor = ArgumentCaptor.forClass(ExtInvoicePaymentReversal.class);
        verify(extInvoicePaymentReversalRepository).save(captor.capture());
        ExtInvoicePaymentReversal saved = captor.getValue();
        assertThat(saved.getPaymentIntentId()).isNull();
        assertThat(saved.getInvoiceId()).isNull();
        assertThat(saved.getPartyId()).isNull();
        assertThat(saved.getRefundId()).isEqualTo(REFUND_ID);
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a duplicate eventId is skipped entirely, without touching the replica")
    void duplicateEventIdSkipped() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        listener().onPaymentEvent(envelope(EVENT_ID, reversal("REFUND", PAYMENT_INTENT_ID, INVOICE_ID, PARTY_ID)));

        verify(extInvoicePaymentReversalRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("the same refundId under a fresh eventId is not re-replicated, but is marked processed")
    void sameRefundIdUnderFreshEventIdSkipped() {
        when(extInvoicePaymentReversalRepository.existsById(REFUND_ID)).thenReturn(true);

        listener().onPaymentEvent(envelope(EVENT_ID, reversal("REFUND", PAYMENT_INTENT_ID, INVOICE_ID, PARTY_ID)));

        verify(extInvoicePaymentReversalRepository, never()).save(any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a malformed payload increments the rejected counter, is marked processed, and nothing is saved")
    void malformedPayloadRejected() {
        String msg = mapper.writeValueAsString(Map.of(
                "eventType",
                PaymentReversedV1.EVENT_TYPE,
                "eventId",
                EVENT_ID,
                "payload",
                Map.of(
                        "refundId",
                        REFUND_ID.toString(),
                        "reversalType",
                        "REFUND",
                        // amount must be a number; an object fails databind
                        "amount",
                        Map.of("nested", "object"),
                        "currencyCode",
                        "USD",
                        "reversedAt",
                        "2026-08-27T00:00:00Z")));

        listener().onPaymentEvent(msg);

        assertThat(rejectedCount()).isEqualTo(1d);
        verify(extInvoicePaymentReversalRepository, never()).save(any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a null reversalType is rejected as malformed, not silently dropped as VOID")
    void nullReversalTypeRejected() {
        listener().onPaymentEvent(envelope(EVENT_ID, reversal(null, PAYMENT_INTENT_ID, INVOICE_ID, PARTY_ID)));

        assertThat(rejectedCount()).isEqualTo(1d);
        verify(extInvoicePaymentReversalRepository, never()).save(any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("an envelope with no payload node is rejected as malformed, not an NPE poison-pill")
    void missingPayloadNodeRejected() {
        String msg = mapper.writeValueAsString(Map.of("eventType", PaymentReversedV1.EVENT_TYPE, "eventId", EVENT_ID));

        listener().onPaymentEvent(msg);

        assertThat(rejectedCount()).isEqualTo(1d);
        verify(extInvoicePaymentReversalRepository, never()).save(any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a repository save failure propagates unmarked, for container retry/DLQ")
    void repositorySaveFailurePropagatesUnmarked() {
        doThrow(new RuntimeException("db unavailable"))
                .when(extInvoicePaymentReversalRepository)
                .save(any(ExtInvoicePaymentReversal.class));

        assertThatThrownBy(() -> listener()
                        .onPaymentEvent(
                                envelope(EVENT_ID, reversal("REFUND", PAYMENT_INTENT_ID, INVOICE_ID, PARTY_ID))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db unavailable");

        verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
    }
}
