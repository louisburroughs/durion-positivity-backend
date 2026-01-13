package com.positivity.accounting.audit.repository;

import com.positivity.accounting.audit.entity.AuditTrailEntry;
import com.positivity.accounting.audit.entity.ExceptionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for audit trail entries.
 */
@Repository
public interface AuditTrailEntryRepository extends JpaRepository<AuditTrailEntry, UUID> {
    
    /**
     * Find all audit entries for a specific order.
     */
    List<AuditTrailEntry> findByOrderIdOrderByTimestampAsc(UUID orderId);
    
    /**
     * Find all audit entries for a specific invoice.
     */
    List<AuditTrailEntry> findByInvoiceIdOrderByTimestampAsc(UUID invoiceId);
    
    /**
     * Find audit entries by exception type within a date range.
     */
    @Query("SELECT a FROM AuditTrailEntry a WHERE a.exceptionType = :type " +
           "AND a.timestamp BETWEEN :startDate AND :endDate " +
           "ORDER BY a.timestamp ASC")
    List<AuditTrailEntry> findByExceptionTypeAndDateRange(
        @Param("type") ExceptionType type,
        @Param("startDate") Instant startDate,
        @Param("endDate") Instant endDate
    );
    
    /**
     * Find audit entries by actor within a date range.
     */
    @Query("SELECT a FROM AuditTrailEntry a WHERE a.actorId = :actorId " +
           "AND a.timestamp BETWEEN :startDate AND :endDate " +
           "ORDER BY a.timestamp ASC")
    List<AuditTrailEntry> findByActorAndDateRange(
        @Param("actorId") UUID actorId,
        @Param("startDate") Instant startDate,
        @Param("endDate") Instant endDate
    );
    
    /**
     * Find all audit entries within a date range.
     */
    @Query("SELECT a FROM AuditTrailEntry a WHERE a.timestamp BETWEEN :startDate AND :endDate " +
           "ORDER BY a.timestamp ASC")
    List<AuditTrailEntry> findByDateRange(
        @Param("startDate") Instant startDate,
        @Param("endDate") Instant endDate
    );
}
