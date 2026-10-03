package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    List<UUID> findIdsByStatusOldestFirst(AccountingEventStatus status, Pageable pageable);

    /**
     * Claim one event for processing: a row lock taken with SKIP LOCKED (lock timeout -2), so a
     * concurrent drainer on another instance skips the row instead of waiting, and empty when the
     * event has meanwhile left {@code status}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    Optional<AccountingEvent> findWithLockByEventIdAndStatus(UUID eventId, AccountingEventStatus status);
}
