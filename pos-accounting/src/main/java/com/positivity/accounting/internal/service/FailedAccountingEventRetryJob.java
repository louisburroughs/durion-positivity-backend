package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.enums.ReprocessingOutcome;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retries failed accounting events on a schedule (#2411). Once per poll and per active tenant
 * (ADR-0062, tenant bound) it takes the oldest {@code FAILED} and {@code SUSPENDED} events that are
 * still under the attempt cap, in a bounded batch, and re-runs each through the posting engine with
 * the current rules via {@link EventIngestionService#reprocessEvent}, as {@code SYSTEM_RETRY_JOB}.
 *
 * <p>It has the transaction shape of {@link ReceivedAccountingEventDrainer}: the candidate ids are
 * read in a short transaction of their own, then each event is claimed with a SKIP LOCKED row lock
 * (so a second instance never retries the same event) and retried in its own transaction, with no
 * outer transaction held open. A failure rolls only that event's transaction back; its outcome is
 * then recorded in a fresh one, so one bad event never takes the rest of the batch with it.
 *
 * <p>Failures a retry cannot change are never candidates: see {@link
 * PostingFailureReason#autoRetryExcludedCodes()} (a closed period, a held currency, an invalid
 * payload, a duplicate-payment quarantine). Those stay for the audited manual reprocess, as does an
 * event that used up its attempts (a WARN names it). {@code RECEIVED} events belong to the drainer.
 *
 * <p>The cadence is slow on purpose (default 15 minutes) because each pass spends one of an event's
 * {@code max-retries} attempts. Set {@code pos.accounting.failed-event-retry.enabled=false} to
 * switch it off.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "pos.accounting.failed-event-retry",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FailedAccountingEventRetryJob {

    static final String RETRY_USER = "SYSTEM_RETRY_JOB";

    private static final List<AccountingEventStatus> RETRYABLE =
            List.of(AccountingEventStatus.FAILED, AccountingEventStatus.SUSPENDED);

    private final AccountingEventRepository eventRepository;
    private final ReprocessingAttemptHistoryRepository historyRepository;
    private final EventIngestionService eventIngestionService;
    private final TenantIterator tenantIterator;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int maxRetries;
    private final int batchSize;

    public FailedAccountingEventRetryJob(
            AccountingEventRepository eventRepository,
            ReprocessingAttemptHistoryRepository historyRepository,
            EventIngestionService eventIngestionService,
            TenantIterator tenantIterator,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${pos.accounting.failed-event-retry.max-retries:3}") int maxRetries,
            @Value("${pos.accounting.failed-event-retry.batch-size:50}") int batchSize) {
        this.eventRepository = eventRepository;
        this.historyRepository = historyRepository;
        this.eventIngestionService = eventIngestionService;
        this.tenantIterator = tenantIterator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.maxRetries = maxRetries;
        this.batchSize = batchSize;
    }

    /** One poll: every active tenant, one tenant's failure not stopping the others. */
    @Scheduled(
            initialDelayString = "${pos.accounting.failed-event-retry.initial-delay-ms:120000}",
            fixedDelayString = "${pos.accounting.failed-event-retry.poll-interval-ms:900000}")
    public void retryFailed() {
        tenantIterator.forEachActiveTenant(tenantId -> {
            int retried = retryBoundTenant();
            if (retried > 0) {
                log.info("Failed-event retry: tenant={} retried={} event(s)", tenantId, retried);
            }
        });
    }

    /**
     * One tenant's pass, with the tenant already bound.
     *
     * @return how many events were retried
     */
    public int retryBoundTenant() {
        List<UUID> ids = transaction.execute(status -> eventRepository.findRetryCandidateIds(
                RETRYABLE, maxRetries, PostingFailureReason.autoRetryExcludedCodes(), PageRequest.of(0, batchSize)));
        int retried = 0;
        for (UUID eventId : ids == null ? List.<UUID>of() : ids) {
            if (retryOne(eventId)) {
                retried++;
            }
        }
        return retried;
    }

    private boolean retryOne(@NonNull UUID eventId) {
        try {
            AccountingEvent after = transaction.execute(status -> eventRepository
                    .findWithLockByEventIdAndStatusIn(eventId, RETRYABLE)
                    .filter(event -> event.getAttemptCount() == null || event.getAttemptCount() < maxRetries)
                    .map(event -> {
                        ReprocessEventRequest request = new ReprocessEventRequest();
                        eventIngestionService.reprocessEvent(eventId, request, RETRY_USER);
                        return event;
                    })
                    .orElse(null));
            if (after == null) {
                return false;
            }
            warnWhenExhausted(after);
            return true;
        } catch (RuntimeException e) {
            log.error("Failed-event retry of accounting event {} failed", eventId, e);
            recordFailure(eventId, e);
            return true;
        }
    }

    /** WARN once an attempt leaves the event unresolved with no attempts left. */
    private void warnWhenExhausted(@NonNull AccountingEvent event) {
        boolean unresolved = event.getStatus() == AccountingEventStatus.FAILED
                || event.getStatus() == AccountingEventStatus.SUSPENDED;
        if (unresolved && event.getAttemptCount() != null && event.getAttemptCount() >= maxRetries) {
            log.warn(
                    "Accounting event {} exhausted its {} automatic retries and stays {} ({}); it needs a manual"
                            + " reprocess",
                    event.getEventId(),
                    maxRetries,
                    event.getStatus(),
                    event.getFailureReasonCode());
        }
    }

    /**
     * Records a failed attempt in a fresh transaction, after the retry's own was rolled back: the
     * attempt count moves on and the history row is written, so the event is not retried forever.
     */
    private void recordFailure(@NonNull UUID eventId, @NonNull RuntimeException failure) {
        String detail = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        try {
            transaction.executeWithoutResult(tx -> eventRepository
                    .findById(eventId)
                    .filter(event -> RETRYABLE.contains(event.getStatus()))
                    .ifPresent(event -> {
                        int attempts = (event.getAttemptCount() == null ? 0 : event.getAttemptCount()) + 1;
                        event.setAttemptCount(attempts);
                        event.setStatus(AccountingEventStatus.FAILED);
                        event.setFailureReasonCode(PostingFailureReason.INTERNAL_ERROR.name());
                        event.setFailureDetails(detail);
                        event.setErrorMessage(detail);
                        eventRepository.save(event);
                        historyRepository.save(history(event, detail));
                        warnWhenExhausted(event);
                    }));
        } catch (RuntimeException e) {
            log.error("Could not record the failed retry of accounting event {}", eventId, e);
        }
    }

    private @NonNull ReprocessingAttemptHistory history(@NonNull AccountingEvent event, @Nullable String detail) {
        ReprocessingAttemptHistory history = new ReprocessingAttemptHistory();
        history.setAccountingEvent(event);
        history.setTriggeredByUserId(RETRY_USER);
        history.setAttemptedAt(Instant.now(clock));
        history.setOutcome(ReprocessingOutcome.FAILURE);
        history.setOutcomeDetails("Exception during automatic retry: " + detail);
        return history;
    }
}
