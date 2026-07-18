package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingPeriod;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * Repository for AccountingPeriod entity.
 */
public interface AccountingPeriodRepository extends JpaRepository<AccountingPeriod, UUID> {

    /**
     * Find a period by its unique {@code YYYY-MM} code.
     */
    Optional<AccountingPeriod> findByPeriodCode(String periodCode);

    /**
     * Locked variant ({@code SELECT ... FOR UPDATE}) for the period gate's
     * closed/hard-lock evaluation ({@code AccountingPeriodGate}): an in-flight
     * gated posting holds the period row until its transaction ends, so a
     * concurrent {@code closePeriod} — which updates the same row — serializes
     * against it instead of closing the period underneath a passed gate check.
     * Read-only API paths ({@code isPeriodOpen}) stay on the unlocked
     * {@link #findByPeriodCode} finder. Must run inside an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountingPeriod> findWithLockByPeriodCode(String periodCode);

    /**
     * List all periods, most recent first (period codes sort lexicographically
     * in chronological order).
     */
    List<AccountingPeriod> findAllByOrderByPeriodCodeDesc();
}
