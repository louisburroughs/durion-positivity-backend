package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ExtInvoiceDepositCreditApplication;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoicePaymentReversalRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.payment.DepositCreditAppliedV1;
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
 * {@code payment.deposit-credit.applied} intake added to {@link SettlementEventsListener} for
 * issue #1621: every draw-down is replicated into {@link ExtInvoiceDepositCreditApplication}.
 * Follows the same "malformed payload skipped-and-marked, business/DB error propagates unmarked"
 * reliability contract proven for {@code payment.payment.settled} by {@link
 * SettlementEventsListenerPaymentSettledTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementEventsListener — payment.deposit-credit.applied intake (issue #1621)")
class SettlementEventsListenerDepositCreditAppliedTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID DEPOSIT_CREDIT_ID = UUID.fromString("01960003-0000-7000-8000-000000000030");
    private static final UUID INVOICE_ID = UUID.fromString("01960003-0000-7000-8000-000000000031");
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
                Map.of("eventType", DepositCreditAppliedV1.EVENT_TYPE, "eventId", eventId, "payload", payload));
    }

    private DepositCreditAppliedV1 applied(BigDecimal amountApplied) {
        return new DepositCreditAppliedV1(
                DEPOSIT_CREDIT_ID, INVOICE_ID, amountApplied, Instant.parse("2026-08-27T00:00:00Z"));
    }

    @Test
    @DisplayName("an applied event is replicated with the payload fields and sourceEventId, then marked"
            + " processed (applicationId is minted at persist per ADR-0013)")
    void appliedEventIsReplicated() {
        listener().onPaymentEvent(envelope(EVENT_ID, applied(new BigDecimal("25.00"))));

        ArgumentCaptor<ExtInvoiceDepositCreditApplication> captor =
                ArgumentCaptor.forClass(ExtInvoiceDepositCreditApplication.class);
        verify(extInvoiceDepositCreditApplicationRepository).save(captor.capture());
        ExtInvoiceDepositCreditApplication saved = captor.getValue();
        assertThat(saved.getDepositCreditId()).isEqualTo(DEPOSIT_CREDIT_ID);
        assertThat(saved.getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(saved.getAmountApplied()).isEqualByComparingTo("25.00");
        assertThat(saved.getAppliedAt()).isEqualTo(Instant.parse("2026-08-27T00:00:00Z"));
        assertThat(saved.getSourceEventId()).isEqualTo(UUID.fromString(EVENT_ID));
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a duplicate (depositCreditId, invoiceId) pair is not re-replicated, but is marked processed")
    void duplicatePairSkipped() {
        when(extInvoiceDepositCreditApplicationRepository.existsByDepositCreditIdAndInvoiceId(
                        DEPOSIT_CREDIT_ID, INVOICE_ID))
                .thenReturn(true);

        listener().onPaymentEvent(envelope(EVENT_ID, applied(new BigDecimal("25.00"))));

        verify(extInvoiceDepositCreditApplicationRepository, never()).save(any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a duplicate eventId is skipped entirely, without touching the replica")
    void duplicateEventIdSkipped() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        listener().onPaymentEvent(envelope(EVENT_ID, applied(new BigDecimal("25.00"))));

        verify(extInvoiceDepositCreditApplicationRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a malformed payload increments the rejected counter, is marked processed, and nothing is saved")
    void malformedPayloadRejected() {
        String msg = mapper.writeValueAsString(Map.of(
                "eventType",
                DepositCreditAppliedV1.EVENT_TYPE,
                "eventId",
                EVENT_ID,
                "payload",
                Map.of(
                        "depositCreditId",
                        DEPOSIT_CREDIT_ID.toString(),
                        "invoiceId",
                        INVOICE_ID.toString(),
                        // amountApplied must be a number; an object fails databind
                        "amountApplied",
                        Map.of("nested", "object"),
                        "appliedAt",
                        "2026-08-27T00:00:00Z")));

        listener().onPaymentEvent(msg);

        assertThat(rejectedCount()).isEqualTo(1d);
        verify(extInvoiceDepositCreditApplicationRepository, never()).save(any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("an envelope with no payload node is rejected as malformed, not an NPE poison-pill")
    void missingPayloadNodeRejected() {
        String msg =
                mapper.writeValueAsString(Map.of("eventType", DepositCreditAppliedV1.EVENT_TYPE, "eventId", EVENT_ID));

        listener().onPaymentEvent(msg);

        assertThat(rejectedCount()).isEqualTo(1d);
        verify(extInvoiceDepositCreditApplicationRepository, never()).save(any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a zero or negative amountApplied is skipped and marked processed, nothing saved")
    void nonPositiveAmountSkipped() {
        listener().onPaymentEvent(envelope(EVENT_ID, applied(BigDecimal.ZERO)));

        verify(extInvoiceDepositCreditApplicationRepository, never()).save(any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }
}
