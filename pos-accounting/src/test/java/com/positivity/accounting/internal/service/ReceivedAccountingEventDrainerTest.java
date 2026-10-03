package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.exception.AccountingEventRejectedException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class ReceivedAccountingEventDrainerTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("0199a000-0000-7000-8000-0000000000e1");

    @Mock
    private AccountingEventRepository eventRepository;

    @Mock
    private InvoicePaymentEventProcessor invoicePaymentProcessor;

    @Mock
    private PostingEngineOrchestrator postingEngineOrchestrator;

    @Mock
    private TenantIterator tenantIterator;

    @Mock
    private ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry;

    private ReceivedAccountingEventDrainer drainer;

    @BeforeEach
    void setUp() {
        drainer = new ReceivedAccountingEventDrainer(
                eventRepository,
                invoicePaymentProcessor,
                postingEngineOrchestrator,
                tenantIterator,
                mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                meterRegistry,
                25,
                600_000L);
    }

    @Test
    @DisplayName("an INVOICE_PAYMENT event goes to the subledger processor, never the posting engine")
    void invoicePayment_dispatchedToProcessor() {
        AccountingEvent event = received(InvoicePaymentEventProcessor.EVENT_TYPE);
        stubBatchOf(event);

        assertThat(drainer.drainBoundTenant()).isEqualTo(1);

        verify(invoicePaymentProcessor).process(event);
        verify(eventRepository).save(event);
        verify(postingEngineOrchestrator, never()).processEvent(any(), any(), anyString(), anyBoolean());
        assertThat(event.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("any other event type goes through the posting engine, auto-posting, as PROCESSING")
    void otherType_dispatchedToPostingEngine() {
        AccountingEvent event = received("billing.invoicePosted");
        stubBatchOf(event);

        assertThat(drainer.drainBoundTenant()).isEqualTo(1);

        verify(postingEngineOrchestrator)
                .processEvent(eq(event), isNull(), eq(ReceivedAccountingEventDrainer.SYSTEM_USER), eq(true));
        verify(invoicePaymentProcessor, never()).process(any());
        assertThat(event.getStatus()).isEqualTo(AccountingEventStatus.PROCESSING);
    }

    @Test
    @DisplayName("a processor rejection is recorded on the event in a fresh transaction")
    void rejection_recordedOnEvent() {
        AccountingEvent event = received(InvoicePaymentEventProcessor.EVENT_TYPE);
        stubBatchOf(event);
        AccountingEvent fresh = received(InvoicePaymentEventProcessor.EVENT_TYPE);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(fresh));
        doThrow(new AccountingEventRejectedException(
                        AccountingEventStatus.SUSPENDED, "INVOICE_NOT_FOUND", "not in the replica yet"))
                .when(invoicePaymentProcessor)
                .process(event);

        assertThat(drainer.drainBoundTenant()).isEqualTo(1);

        assertThat(fresh.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(fresh.getFailureReasonCode()).isEqualTo("INVOICE_NOT_FOUND");
        assertThat(fresh.getErrorMessage()).isEqualTo("not in the replica yet");
        assertThat(fresh.getAttemptCount()).isEqualTo(1);
        assertThat(fresh.getProcessedAt()).isNull();
        verify(eventRepository).save(fresh);
    }

    @Test
    @DisplayName("an unexpected failure marks the event FAILED / INTERNAL_ERROR so it does not loop")
    void unexpectedFailure_failedInternalError() {
        AccountingEvent event = received(InvoicePaymentEventProcessor.EVENT_TYPE);
        stubBatchOf(event);
        AccountingEvent fresh = received(InvoicePaymentEventProcessor.EVENT_TYPE);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(fresh));
        doThrow(new IllegalStateException("boom")).when(invoicePaymentProcessor).process(event);

        assertThat(drainer.drainBoundTenant()).isEqualTo(1);

        assertThat(fresh.getStatus()).isEqualTo(AccountingEventStatus.FAILED);
        assertThat(fresh.getFailureReasonCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(fresh.getErrorMessage()).contains("boom");
        assertThat(fresh.getProcessedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a unique-key race leaves the event RECEIVED for the next poll, counting the attempt")
    void integrityRace_retriedOnNextPoll() {
        AccountingEvent event = received(InvoicePaymentEventProcessor.EVENT_TYPE);
        stubBatchOf(event);
        AccountingEvent fresh = received(InvoicePaymentEventProcessor.EVENT_TYPE);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(fresh));
        doThrow(new DataIntegrityViolationException("duplicate key receivable_payment_pkey"))
                .when(invoicePaymentProcessor)
                .process(event);

        assertThat(drainer.drainBoundTenant()).isEqualTo(1);

        assertThat(fresh.getStatus()).isEqualTo(AccountingEventStatus.RECEIVED);
        assertThat(fresh.getAttemptCount()).isEqualTo(1);
        assertThat(fresh.getFailureReasonCode()).isNull();
        verify(eventRepository).save(fresh);
    }

    @Test
    @DisplayName("a unique-key violation that keeps recurring is failed instead of retried forever")
    void integrityRace_recurring_failed() {
        AccountingEvent event = received(InvoicePaymentEventProcessor.EVENT_TYPE);
        stubBatchOf(event);
        AccountingEvent fresh = received(InvoicePaymentEventProcessor.EVENT_TYPE);
        fresh.setAttemptCount(ReceivedAccountingEventDrainer.MAX_INTEGRITY_RETRIES - 1);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(fresh));
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(invoicePaymentProcessor)
                .process(event);

        drainer.drainBoundTenant();

        assertThat(fresh.getStatus()).isEqualTo(AccountingEventStatus.FAILED);
        assertThat(fresh.getFailureReasonCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(fresh.getProcessedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("each poll publishes the stale RECEIVED backlog and tags drained events by their final status")
    @SuppressWarnings("unchecked")
    void metrics_staleGaugeAndOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(meterRegistry.getIfAvailable()).thenReturn(registry);
        ReceivedAccountingEventDrainer metered = new ReceivedAccountingEventDrainer(
                eventRepository,
                invoicePaymentProcessor,
                postingEngineOrchestrator,
                tenantIterator,
                mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                meterRegistry,
                25,
                600_000L);
        AccountingEvent event = received("billing.invoicePosted");
        stubBatchOf(event);
        doAnswer(inv -> {
                    AccountingEvent claimed = inv.getArgument(0);
                    claimed.setStatus(AccountingEventStatus.SUSPENDED);
                    return null;
                })
                .when(postingEngineOrchestrator)
                .processEvent(any(), any(), anyString(), anyBoolean());
        when(eventRepository.countByStatusAndReceivedAtBefore(
                        AccountingEventStatus.RECEIVED, NOW.minusMillis(600_000L)))
                .thenReturn(4L);
        doAnswer(inv -> {
                    ((Consumer<UUID>) inv.getArgument(0)).accept(UUID.randomUUID());
                    return 1;
                })
                .when(tenantIterator)
                .forEachActiveTenant(any());

        metered.drain();

        assertThat(registry.get("accounting.events.received.stale").gauge().value())
                .isEqualTo(4.0);
        assertThat(registry.get("accounting.events.drained")
                        .tag("outcome", "suspended")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("an event claimed elsewhere or no longer RECEIVED is skipped untouched")
    void notClaimed_skipped() {
        when(eventRepository.findIdsByStatusOldestFirst(eq(AccountingEventStatus.RECEIVED), any(Pageable.class)))
                .thenReturn(List.of(EVENT_ID));
        when(eventRepository.findWithLockByEventIdAndStatus(EVENT_ID, AccountingEventStatus.RECEIVED))
                .thenReturn(Optional.empty());

        assertThat(drainer.drainBoundTenant()).isZero();

        verify(invoicePaymentProcessor, never()).process(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("each poll drains every active tenant, reading a bounded batch")
    @SuppressWarnings("unchecked")
    void drain_runsPerTenantWithBatchSize() {
        when(eventRepository.findIdsByStatusOldestFirst(eq(AccountingEventStatus.RECEIVED), any(Pageable.class)))
                .thenReturn(List.of());

        drainer.drain();

        ArgumentCaptor<Consumer<UUID>> work = ArgumentCaptor.forClass(Consumer.class);
        verify(tenantIterator).forEachActiveTenant(work.capture());
        work.getValue().accept(UUID.randomUUID());
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(eventRepository).findIdsByStatusOldestFirst(eq(AccountingEventStatus.RECEIVED), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(25);
    }

    private void stubBatchOf(AccountingEvent event) {
        when(eventRepository.findIdsByStatusOldestFirst(eq(AccountingEventStatus.RECEIVED), any(Pageable.class)))
                .thenReturn(List.of(event.getEventId()));
        when(eventRepository.findWithLockByEventIdAndStatus(event.getEventId(), AccountingEventStatus.RECEIVED))
                .thenReturn(Optional.of(event));
    }

    private static AccountingEvent received(String eventType) {
        AccountingEvent event = new AccountingEvent();
        event.setEventId(EVENT_ID);
        event.setEventType(eventType);
        event.setStatus(AccountingEventStatus.RECEIVED);
        return event;
    }
}
