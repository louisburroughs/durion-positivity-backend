package com.positivity.order.repository;

import com.positivity.order.model.ApprovalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for ApprovalRecord entity operations.
 */
@Repository
public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, Long> {
    
    /**
     * Find all approval records for a specific price override.
     */
    List<ApprovalRecord> findByPriceOverrideId(Long priceOverrideId);
    
    /**
     * Find all approval actions by a specific reviewer.
     */
    List<ApprovalRecord> findByReviewerUserId(String reviewerUserId);
    
    /**
     * Find approval records within a date range.
     */
    List<ApprovalRecord> findByActionTimestampBetween(Instant startDate, Instant endDate);
    
    /**
     * Find records by action type.
     */
    List<ApprovalRecord> findByAction(String action);
}
