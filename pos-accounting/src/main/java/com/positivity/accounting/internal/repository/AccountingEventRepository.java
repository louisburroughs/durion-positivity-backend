package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

/**
 * Repository for AccountingEvent entity.
 * Supports CRUD, specification-based queries and source-system pagination.
 */
public interface AccountingEventRepository
        extends JpaRepository<AccountingEvent, UUID>, JpaSpecificationExecutor<AccountingEvent> {

    /**
     * Find accounting events by source system.
     * Uses indexed column for efficient querying.
     */
    Page<AccountingEvent> findBySourceSystem(String sourceSystem, Pageable pageable);

    /**
     * Whether an ingestion record already holds this fact for this reason, so a redelivered fact
     * held for its currency does not write a second record (issue #2312).
     */
    boolean existsByEventTypeAndDomainKeyIdAndFailureReasonCode(
            String eventType, String domainKeyId, String failureReasonCode);

    /**
     * Ids of the oldest events in {@code status}, for the received-event drainer (#2435). Ids only:
     * each event is then claimed and processed in its own transaction.
     */
    @Query("select e.eventId from AccountingEvent e where e.status = :status order by e.receivedAt asc")
    @NonNull
    List<UUID> findIdsByStatusOldestFirst(@NonNull AccountingEventStatus status, @NonNull Pageable pageable);

    /**
     * Ids of the oldest events in any of {@code statuses} that have used fewer than {@code
     * maxAttempts} attempts (a null count is zero) and whose failure reason is not in {@code
     * excludedReasonCodes}: the bounded candidate list of the scheduled failed-event retry (#2411).
     * Ids only: each event is then claimed and retried in its own transaction.
     */
    @Query("select e.eventId from AccountingEvent e where e.status in :statuses"
            + " and (e.attemptCount is null or e.attemptCount < :maxAttempts)"
            + " and (e.failureReasonCode is null or e.failureReasonCode not in :excludedReasonCodes)"
            + " order by e.receivedAt asc")
    @NonNull
    List<UUID> findRetryCandidateIds(
            @NonNull Collection<AccountingEventStatus> statuses,
            int maxAttempts,
            @NonNull Collection<String> excludedReasonCodes,
            @NonNull Pageable pageable);

    /**
     * Claim one event for the failed-event retry: like {@link #findWithLockByEventIdAndStatus}, but
     * the event may be in any of {@code statuses}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    Optional<AccountingEvent> findWithLockByEventIdAndStatusIn(
            @NonNull UUID eventId, @NonNull Collection<AccountingEventStatus> statuses);

    /**
     * Claim one event for processing: a row lock taken with SKIP LOCKED (lock timeout -2), so a
     * concurrent drainer on another instance skips the row instead of waiting, and empty when the
     * event has meanwhile left {@code status}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    Optional<AccountingEvent> findWithLockByEventIdAndStatus(
            @NonNull UUID eventId, @NonNull AccountingEventStatus status);

    /**
     * How many events have been in {@code status} since before {@code cutoff}: the stale-backlog
     * gauge of the received-event drainer (#2435).
     */
    long countByStatusAndReceivedAtBefore(@NonNull AccountingEventStatus status, @NonNull Instant cutoff);
}
