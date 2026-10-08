package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.tenancy.TenantAudited;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * Share-locked variant ({@code SELECT ... FOR SHARE}) for the AP pay command's period check before the gateway
     * (CAP:550 S42, #2603): a {@code closePeriod} waits for the payment as it waits for a posting, while payments of the
     * same month, which only read the row, do not wait for each other across their gateway calls. Must run inside an
     * active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<AccountingPeriod> findWithShareLockByPeriodCode(String periodCode);

    /**
     * List all periods, most recent first (period codes sort lexicographically
     * in chronological order).
     */
    List<AccountingPeriod> findAllByOrderByPeriodCodeDesc();

    /**
     * Whether the tenant has ever closed a period: one is CLOSED now, or one was closed and reopened (a reopen keeps
     * {@code closed_at}). Once true the accounting time zone is fixed (#2558).
     */
    boolean existsByStatusOrClosedAtIsNotNull(@NonNull AccountingPeriodStatus status);

    /**
     * Create an OPEN period row unless one exists for the tenant and {@code periodCode} (#2342).
     * {@code ON CONFLICT DO NOTHING} keeps a concurrent auto-provision from throwing, so it runs in
     * the caller's transaction on the caller's connection and never marks it rollback-only; the
     * target-less form works on Postgres and on H2 in PostgreSQL mode. Native SQL skips the entity
     * callbacks, so the audit columns and version are passed explicitly.
     *
     * @return 1 if this call inserted the row, 0 if the period already existed
     */
    @TenantAudited(
            reason = "names the tenant explicitly (the caller's resolved tenant), so the row is the bound tenant's on"
                    + " Postgres and on the H2 slices alike; the policy's WITH CHECK still refuses any other tenant")
    @Modifying
    @Query(value = """
                    INSERT INTO accounting_period
                        (tenant_id, period_id, period_code, start_date, end_date, status,
                         created_at, created_by, modified_at, modified_by, version)
                    VALUES (:tenantId, :periodId, :periodCode, :startDate, :endDate, 'OPEN',
                            :now, :actor, :now, :actor, 0)
                    ON CONFLICT DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("tenantId") UUID tenantId,
            @Param("periodId") UUID periodId,
            @Param("periodCode") String periodCode,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("now") Instant now,
            @Param("actor") String actor);
}
