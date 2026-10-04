package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.exception.AccountingEventRejectedException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
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
 * the retry endpoint re-runs a {@code FAILED} event through the posting engine itself (an {@code
 * INVOICE_PAYMENT} goes back to {@code RECEIVED} for the next poll).
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

    /**
     * Polls an event may lose to a unique-key race (two instances recording the same payment) before
     * it is failed instead of retried.
     */
    static final int MAX_INTEGRITY_RETRIES = 3;

    private final AccountingEventRepository eventRepository;
    private final InvoicePaymentEventProcessor invoicePaymentProcessor;
    private final PostingEngineOrchestrator postingEngineOrchestrator;
    private final TenantIterator tenantIterator;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int batchSize;
    private final Duration staleAfter;
    private final @Nullable MeterRegistry meterRegistry;
    private final AtomicLong staleReceived = new AtomicLong();

    public ReceivedAccountingEventDrainer(
            AccountingEventRepository eventRepository,
            InvoicePaymentEventProcessor invoicePaymentProcessor,
            PostingEngineOrchestrator postingEngineOrchestrator,
            TenantIterator tenantIterator,
            PlatformTransactionManager transactionManager,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${pos.accounting.event-drainer.batch-size:50}") int batchSize,
            @Value("${pos.accounting.event-drainer.stale-after-ms:600000}") long staleAfterMs) {
        this.eventRepository = eventRepository;
        this.invoicePaymentProcessor = invoicePaymentProcessor;
        this.postingEngineOrchestrator = postingEngineOrchestrator;
        this.tenantIterator = tenantIterator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = batchSize;
        this.staleAfter = Duration.ofMillis(staleAfterMs);
        this.meterRegistry = meterRegistry.getIfAvailable();
        if (this.meterRegistry != null) {
            Gauge.builder("accounting.events.received.stale", staleReceived, AtomicLong::get)
                    .description("Accounting events still RECEIVED longer than the drainer's stale-after"
                            + " threshold, across all tenants, as of the last poll")
                    .register(this.meterRegistry);
        }
    }

    /** One poll: every active tenant, one tenant's failure not stopping the others. */
    @Scheduled(
            initialDelayString = "${pos.accounting.event-drainer.initial-delay-ms:60000}",
            fixedDelayString = "${pos.accounting.event-drainer.poll-interval-ms:30000}")
    public void drain() {
        AtomicLong stale = new AtomicLong();
        tenantIterator.forEachActiveTenant(tenantId -> {
            int drained = drainBoundTenant();
            if (drained > 0) {
                log.info("Received-event drainer: tenant={} drained={} event(s)", tenantId, drained);
            }
            stale.addAndGet(countStaleForBoundTenant());
        });
        staleReceived.set(stale.get());
    }

    /** {@code RECEIVED} events older than the stale-after threshold, for the bound tenant. */
    long countStaleForBoundTenant() {
        Instant cutoff = Instant.now(clock).minus(staleAfter);
        Long count = transaction.execute(
                status -> eventRepository.countByStatusAndReceivedAtBefore(AccountingEventStatus.RECEIVED, cutoff));
        return count == null ? 0L : count;
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
            AccountingEventStatus outcome = transaction.execute(status -> eventRepository
                    .findWithLockByEventIdAndStatus(eventId, AccountingEventStatus.RECEIVED)
                    .map(this::dispatch)
                    .orElse(null));
            if (outcome == null) {
                return false;
            }
            count(outcome);
            return true;
        } catch (AccountingEventRejectedException rejected) {
            log.warn(
                    "Accounting event {} rejected: {} {} - {}",
                    eventId,
                    rejected.getStatus(),
                    rejected.getReasonCode(),
                    rejected.getMessage());
            recordOutcome(eventId, rejected.getStatus(), rejected.getReasonCode(), rejected.getMessage());
            count(rejected.getStatus());
            return true;
        } catch (DataIntegrityViolationException race) {
            // Most likely another instance recorded the same payment between this event's read and its
            // insert: leave the event RECEIVED so the next poll sees the committed row and takes the
            // duplicate path. A violation that keeps recurring is failed rather than retried forever.
            log.warn("Accounting event {} lost a unique-key race; retrying on the next poll", eventId, race);
            recordIntegrityRetry(eventId, race);
            return true;
        } catch (RuntimeException e) {
            log.error("Accounting event {} failed while draining", eventId, e);
            recordOutcome(
                    eventId,
                    AccountingEventStatus.FAILED,
                    PostingFailureReason.INTERNAL_ERROR.name(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            count(AccountingEventStatus.FAILED);
            return true;
        }
    }

    private void recordIntegrityRetry(@NonNull UUID eventId, @NonNull RuntimeException race) {
        String detail = race.getClass().getSimpleName() + ": " + race.getMessage();
        try {
            transaction.executeWithoutResult(tx -> eventRepository
                    .findById(eventId)
                    .filter(event -> event.getStatus() == AccountingEventStatus.RECEIVED)
                    .ifPresent(event -> {
                        int attempt = nextAttempt(event);
                        event.setAttemptCount(attempt);
                        event.setErrorMessage(detail);
                        if (attempt >= MAX_INTEGRITY_RETRIES) {
                            event.setStatus(AccountingEventStatus.FAILED);
                            event.setFailureReasonCode(PostingFailureReason.INTERNAL_ERROR.name());
                            event.setFailureDetails(detail);
                            event.setProcessedAt(Instant.now(clock));
                        }
                        eventRepository.save(event);
                    }));
        } catch (RuntimeException e) {
            log.error("Could not record the retry for accounting event {}", eventId, e);
        }
    }

    /** Process one claimed event; returns the status it ended in. */
    private @NonNull AccountingEventStatus dispatch(@NonNull AccountingEvent event) {
        event.setAttemptCount(nextAttempt(event));
        if (InvoicePaymentEventProcessor.EVENT_TYPE.equals(event.getEventType())) {
            invoicePaymentProcessor.process(event);
            eventRepository.save(event);
            return event.getStatus();
        }
        // The posting engine records its own outcome (PROCESSED, SUSPENDED or FAILED) on the event.
        event.setStatus(AccountingEventStatus.PROCESSING);
        eventRepository.save(event);
        postingEngineOrchestrator.processEvent(event, null, SYSTEM_USER, true);
        return event.getStatus();
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

    private void count(@NonNull AccountingEventStatus outcome) {
        if (meterRegistry != null) {
            Counter.builder("accounting.events.drained")
                    .description("Received accounting events drained, by the status they ended in")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .register(meterRegistry)
                    .increment();
        }
    }
}
