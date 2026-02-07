package com.positivity.order.internal.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.order.internal.entity.ApprovalRecord;

/**
 * Repository for ApprovalRecord entity operations.
 */
@Repository
public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, UUID> {

    /**
     * Find all approval records for a specific price override.
     */
    List<ApprovalRecord> findByPriceOverrideId(UUID priceOverrideId);

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
