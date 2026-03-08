package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.AccountingStatusChangedEvent;
import com.positivity.accounting.internal.dto.AccountingStatusView;
import com.positivity.accounting.internal.entity.AccountingStatusSyncAudit;
import com.positivity.accounting.internal.entity.InvoiceStatusView;
import com.positivity.accounting.internal.enums.AccountingStatus;
import com.positivity.accounting.internal.repository.AccountingStatusSyncAuditRepository;
import com.positivity.accounting.internal.repository.InvoiceStatusViewRepository;
import com.positivity.accounting.service.AccountingStatusSyncService;
import com.positivity.accounting.service.IdempotencyService;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class AccountingStatusSyncServiceImpl implements AccountingStatusSyncService {

    private static final String INVOICE_STATUS_NOT_FOUND = "InvoiceStatusView not found for invoiceId: ";

    private final InvoiceStatusViewRepository statusViewRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;
    private final AccountingStatusSyncAuditRepository auditRepository;

    public AccountingStatusSyncServiceImpl(
            InvoiceStatusViewRepository statusViewRepository,
            IdempotencyService idempotencyService,
            Clock clock,
            AccountingStatusSyncAuditRepository auditRepository) {
        this.statusViewRepository = statusViewRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
        this.auditRepository = auditRepository;
    }

    @Override
    @Transactional
    @Retryable(maxAttempts = 10, backoff = @Backoff(delay = 500, multiplier = 2),
            retryFor = { DataAccessException.class, ObjectOptimisticLockingFailureException.class },
            noRetryFor = { jakarta.persistence.EntityNotFoundException.class })
    public void processStatusEvent(@NonNull AccountingStatusChangedEvent event) {
        if (idempotencyService.isKeyProcessed(event.getEventId())) {
            log.info("Duplicate accounting status event ignored: eventId={}", event.getEventId());
            return;
        }

        InvoiceStatusView statusView = statusViewRepository.findByInvoiceId(event.getInvoiceId())
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        INVOICE_STATUS_NOT_FOUND + event.getInvoiceId()));

        if (statusView.getAccountingStatusUpdatedAt() != null
                && event.getTimestamp().isBefore(statusView.getAccountingStatusUpdatedAt())) {
            log.warn("Out-of-order accounting status event ignored: event timestamp {} is before current updatedAt {} for invoice {}",
                    event.getTimestamp(), statusView.getAccountingStatusUpdatedAt(), event.getInvoiceId());
            return;
        }

        AccountingStatus currentStatus = statusView.getAccountingStatus();

        if (currentStatus != null && isBackwardTransition(currentStatus, event.getNewStatus())) {
            log.warn("Backward accounting status transition rejected: {} → {}; event {} ignored.",
                    currentStatus, event.getNewStatus(), event.getEventId());
            return;
        }

        AccountingStatus oldStatus = statusView.getAccountingStatus();

        statusView.setAccountingStatus(event.getNewStatus());
        statusView.setAccountingStatusUpdatedAt(Instant.now(clock));
        if (event.getPostingReference() != null) {
            statusView.setPostingReference(event.getPostingReference());
        }

        if (event.getNewStatus() == AccountingStatus.REJECTED) {
            statusView.setDiscrepancyDetected(true);
            statusView.setDiscrepancyReason(event.getDiscrepancyReason());
        } else {
            statusView.setDiscrepancyDetected(false);
            statusView.setDiscrepancyReason(null);
        }

        statusViewRepository.save(statusView);
        idempotencyService.registerKey(event.getEventId(), event.getInvoiceId());

        Instant syncedAt = Instant.now(clock);
        Long latencyMs = event.getTimestamp() != null
                ? Duration.between(event.getTimestamp(), syncedAt).toMillis()
                : null;
        auditRepository.save(AccountingStatusSyncAudit.builder()
                .id(UUIDv7Generator.generate())
                .invoiceId(event.getInvoiceId())
                .oldStatus(oldStatus)
                .newStatus(event.getNewStatus())
                .eventId(event.getEventId())
                .syncedAt(syncedAt)
                .syncSource("pos-accounting")
                .latencyMs(latencyMs)
                .build());

        log.info("Processed accounting status event: invoiceId={}, {} → {}, eventId={}",
                event.getInvoiceId(), oldStatus, event.getNewStatus(), event.getEventId());
    }

    @Recover
    public void recoverProcessStatusEvent(Exception e, @NonNull AccountingStatusChangedEvent event) {
        log.error("Retry exhausted for processStatusEvent: eventId={}, invoiceId={}. Cause: {}",
                event.getEventId(), event.getInvoiceId(), e.getMessage(), e);
        throw new IllegalStateException(
                "Accounting status event processing failed after retries: " + event.getEventId(), e);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull AccountingStatusView getInvoiceAccountingStatus(@NonNull UUID invoiceId) {
        return buildAccountingStatusView(invoiceId);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull AccountingStatusView getInvoiceAccountingStatusDetail(@NonNull UUID invoiceId) {
        return buildAccountingStatusView(invoiceId);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull AccountingStatusView refreshAccountingStatus(@NonNull UUID invoiceId) {
        return buildAccountingStatusView(invoiceId);
    }

    private @NonNull AccountingStatusView buildAccountingStatusView(@NonNull UUID invoiceId) {
        InvoiceStatusView statusView = statusViewRepository.findByInvoiceId(invoiceId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        INVOICE_STATUS_NOT_FOUND + invoiceId));
        Instant updatedAt = statusView.getAccountingStatusUpdatedAt();
        boolean stale = updatedAt == null
                || Instant.now(clock).minus(1, ChronoUnit.HOURS).isAfter(updatedAt);
        return AccountingStatusView.builder()
                .invoiceId(statusView.getInvoiceId())
                .accountingStatus(statusView.getAccountingStatus())
                .accountingStatusUpdatedAt(statusView.getAccountingStatusUpdatedAt())
                .postingReference(statusView.getPostingReference())
                .discrepancyDetected(statusView.isDiscrepancyDetected())
                .discrepancyReason(statusView.getDiscrepancyReason())
                .stale(stale)
                .build();
    }

    /**
     * Determines if transitioning from {@code current} to {@code proposed} is a
     * backward transition.
     * Terminal states (POSTED, RECONCILED, REJECTED, REVERSED, VOIDED) may not
     * regress to early
     * states (PENDING_POSTING, ON_HOLD).
     *
     * <p>
     * DISPUTED is intentionally classified as an interruptible state: it may
     * transition back to
     * PENDING_POSTING to allow re-processing after dispute resolution (CAP-251 #5).
     */
    private static boolean isBackwardTransition(AccountingStatus current, AccountingStatus proposed) {
        Set<AccountingStatus> terminalStates = EnumSet.of(
                AccountingStatus.POSTED, AccountingStatus.RECONCILED,
                AccountingStatus.REJECTED, AccountingStatus.REVERSED, AccountingStatus.VOIDED);
        Set<AccountingStatus> earlyStates = EnumSet.of(
                AccountingStatus.PENDING_POSTING, AccountingStatus.ON_HOLD);
        return terminalStates.contains(current) && earlyStates.contains(proposed);
    }
}
