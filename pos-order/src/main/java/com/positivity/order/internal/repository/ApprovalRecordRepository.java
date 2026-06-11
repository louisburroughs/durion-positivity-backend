package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ApprovalRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for ApprovalRecord entity operations.
 */
public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, UUID> {

    /**
     * Find all approval records for a specific price override.
     */
    List<ApprovalRecord> findByPriceOverride_OverrideId(UUID priceOverrideId);

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
