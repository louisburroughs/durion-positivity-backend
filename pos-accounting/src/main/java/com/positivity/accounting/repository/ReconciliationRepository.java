package com.positivity.accounting.repository;

import com.positivity.accounting.entity.Reconciliation;
import com.positivity.accounting.enums.ReconciliationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Reconciliation entities.
 * 
 * @see Reconciliation
 */
@Repository
public interface ReconciliationRepository extends JpaRepository<Reconciliation, String> {

    /**
     * Find all reconciliations for a GL account.
     */
    List<Reconciliation> findByGlAccountId(String glAccountId);

    /**
     * Find reconciliations by status.
     */
    List<Reconciliation> findByStatus(ReconciliationStatus status);

    /**
     * Find reconciliation for a GL account and period.
     */
    @Query("SELECT r FROM Reconciliation r WHERE r.glAccountId = :glAccountId " +
            "AND r.periodStartDate = :periodStartDate AND r.periodEndDate = :periodEndDate")
    Optional<Reconciliation> findByAccountAndPeriod(
            @Param("glAccountId") String glAccountId,
            @Param("periodStartDate") LocalDate periodStartDate,
            @Param("periodEndDate") LocalDate periodEndDate);

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
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
