package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.Reconciliation;
import com.positivity.accounting.internal.enums.ReconciliationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for Reconciliation entities.
 *
 * @see Reconciliation
 */
public interface ReconciliationRepository extends JpaRepository<Reconciliation, UUID> {

    /**
     * Find all reconciliations for a GL account.
     */
    List<Reconciliation> findByGlAccount_GlAccountId(UUID glAccountId);

    /**
     * Find reconciliations by status.
     */
    List<Reconciliation> findByStatus(ReconciliationStatus status);

    /**
     * Find reconciliation for a GL account and period.
     */
    @Query("SELECT r FROM Reconciliation r WHERE r.glAccount.glAccountId = :glAccountId "
            + "AND r.periodStartDate = :periodStartDate AND r.periodEndDate = :periodEndDate")
    Optional<Reconciliation> findByAccountAndPeriod(
            @Param("glAccountId") UUID glAccountId,
            @Param("periodStartDate") LocalDateTime periodStartDate,
            @Param("periodEndDate") LocalDateTime periodEndDate);

    /**
     * Find reconciliations with unresolved differences.
     */
    @Query("SELECT r FROM Reconciliation r WHERE r.status = :status AND r.difference <> 0")
    List<Reconciliation> findUnresolvedByStatus(@Param("status") ReconciliationStatus status);

    /**
     * Find reconciliations by period range.
     */
    @Query("SELECT r FROM Reconciliation r WHERE r.periodEndDate BETWEEN :startDate AND :endDate")
    List<Reconciliation> findByPeriodRange(
            @Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);
}
