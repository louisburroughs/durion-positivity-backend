package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.exception.AccountingEventRejectedException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains accounting events accepted as {@code RECEIVED} through {@code POST /v1/accounting/events}
 * (#2435). Submission only persists an event; before this job nothing moved it on, so every
 * submitted event stayed {@code RECEIVED} for good.
 *
 * <p>Once per poll and per active tenant (ADR-0062, tenant bound), it takes the oldest {@code
 * RECEIVED} events in a bounded batch and processes each in its own transaction, claimed with a
 * SKIP LOCKED row lock so a second instance never processes the same event. {@code INVOICE_PAYMENT}
 * goes to {@link InvoicePaymentEventProcessor} (AR subledger, no journal entry of its own); every
 * other type goes through the posting engine, which posts it or suspends it with a reason. Only
 * {@code RECEIVED} rows are touched: {@code SUSPENDED} events are released by reprocess alone, and
 * the retry endpoint puts a {@code FAILED} event back to {@code RECEIVED} for the next poll.
 *
 * <p>A processor rejection or an unexpected failure rolls the processing transaction back; the
 * outcome is then recorded on the event in a fresh transaction, so a failing event never blocks the
 * rest of the batch or comes back on the next poll.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "pos.accounting.event-drainer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReceivedAccountingEventDrainer {

    static final String SYSTEM_USER = "SYSTEM";

    private final AccountingEventRepository eventRepository;
    private final InvoicePaymentEventProcessor invoicePaymentProcessor;
    private final PostingEngineOrchestrator postingEngineOrchestrator;
    private final TenantIterator tenantIterator;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int batchSize;
    private final @Nullable MeterRegistry meterRegistry;

    public ReceivedAccountingEventDrainer(
            AccountingEventRepository eventRepository,
            InvoicePaymentEventProcessor invoicePaymentProcessor,
            PostingEngineOrchestrator postingEngineOrchestrator,
            TenantIterator tenantIterator,
            PlatformTransactionManager transactionManager,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${pos.accounting.event-drainer.batch-size:50}") int batchSize) {
        this.eventRepository = eventRepository;
        this.invoicePaymentProcessor = invoicePaymentProcessor;
        this.postingEngineOrchestrator = postingEngineOrchestrator;
        this.tenantIterator = tenantIterator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = batchSize;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /** One poll: every active tenant, one tenant's failure not stopping the others. */
    @Scheduled(
            initialDelayString = "${pos.accounting.event-drainer.initial-delay-ms:60000}",
            fixedDelayString = "${pos.accounting.event-drainer.poll-interval-ms:30000}")
    public void drain() {
        tenantIterator.forEachActiveTenant(tenantId -> {
            int drained = drainBoundTenant();
            if (drained > 0) {
                log.info("Received-event drainer: tenant={} drained={} event(s)", tenantId, drained);
            }
        });
    }

    /**
     * One tenant's pass, with the tenant already bound: the oldest batch of {@code RECEIVED}
     * events, each in its own transaction.
     *
     * @return how many events left {@code RECEIVED}
     */
    public int drainBoundTenant() {
        List<UUID> ids = transaction.execute(status -> eventRepository.findIdsByStatusOldestFirst(
                AccountingEventStatus.RECEIVED, PageRequest.of(0, batchSize)));
        int drained = 0;
        for (UUID eventId : ids == null ? List.<UUID>of() : ids) {
            if (drainOne(eventId)) {
                drained++;
            }
        }
        return drained;
    }

    private boolean drainOne(@NonNull UUID eventId) {
        try {
            Boolean claimed = transaction.execute(status -> eventRepository
                    .findWithLockByEventIdAndStatus(eventId, AccountingEventStatus.RECEIVED)
                    .map(event -> {
                        dispatch(event);
                        return Boolean.TRUE;
                    })
                    .orElse(Boolean.FALSE));
            if (Boolean.TRUE.equals(claimed)) {
                count("processed");
                return true;
            }
            return false;
        } catch (AccountingEventRejectedException rejected) {
            log.warn(
                    "Accounting event {} rejected: {} {} - {}",
                    eventId,
                    rejected.getStatus(),
                    rejected.getReasonCode(),
                    rejected.getMessage());
            recordOutcome(eventId, rejected.getStatus(), rejected.getReasonCode(), rejected.getMessage());
            count(rejected.getStatus().name().toLowerCase(Locale.ROOT));
            return true;
        } catch (RuntimeException e) {
            log.error("Accounting event {} failed while draining", eventId, e);
            recordOutcome(
                    eventId,
                    AccountingEventStatus.FAILED,
                    PostingFailureReason.INTERNAL_ERROR.name(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            count("failed");
            return true;
        }
    }

    private void dispatch(@NonNull AccountingEvent event) {
        event.setAttemptCount(nextAttempt(event));
        if (InvoicePaymentEventProcessor.EVENT_TYPE.equals(event.getEventType())) {
            invoicePaymentProcessor.process(event);
            eventRepository.save(event);
            return;
        }
        // The posting engine records its own outcome (PROCESSED, SUSPENDED or FAILED) on the event.
        event.setStatus(AccountingEventStatus.PROCESSING);
        eventRepository.save(event);
        postingEngineOrchestrator.processEvent(event, null, SYSTEM_USER, true);
    }

    private void recordOutcome(
            @NonNull UUID eventId,
            @NonNull AccountingEventStatus status,
            @NonNull String reasonCode,
            @Nullable String detail) {
        try {
            transaction.executeWithoutResult(tx -> eventRepository
                    .findById(eventId)
                    .filter(event -> event.getStatus() == AccountingEventStatus.RECEIVED
                            || event.getStatus() == AccountingEventStatus.PROCESSING)
                    .ifPresent(event -> {
                        event.setStatus(status);
                        event.setFailureReasonCode(reasonCode);
                        event.setFailureDetails(detail);
                        event.setErrorMessage(detail);
                        event.setAttemptCount(nextAttempt(event));
                        if (status == AccountingEventStatus.FAILED) {
                            event.setProcessedAt(Instant.now(clock));
                        }
                        eventRepository.save(event);
                    }));
        } catch (RuntimeException e) {
            // Left RECEIVED: the next poll tries again.
            log.error("Could not record outcome {} for accounting event {}", status, eventId, e);
        }
    }

    private static int nextAttempt(@NonNull AccountingEvent event) {
        return (event.getAttemptCount() == null ? 0 : event.getAttemptCount()) + 1;
    }

    private void count(@NonNull String outcome) {
        if (meterRegistry != null) {
            Counter.builder("accounting.events.drained")
                    .description("Received accounting events drained, by outcome")
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }
}
