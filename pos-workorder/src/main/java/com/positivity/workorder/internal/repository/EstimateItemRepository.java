package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ApprovalStatus;
import com.positivity.workorder.internal.entity.EstimateItem;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EstimateItemRepository extends JpaRepository<EstimateItem, UUID> {

    /**
     * Find all items for a given estimate (excluding soft-deleted items).
     */
    @NonNull
    List<EstimateItem> findByEstimateIdAndDeletedFalse(@NonNull UUID estimateId);

    /**
     * Find a specific item for an estimate (excluding soft-deleted items).
     */
    @NonNull
    Optional<EstimateItem> findByIdAndEstimateIdAndDeletedFalse(@NonNull UUID id, @NonNull UUID estimateId);

    /**
     * Count non-deleted items for an estimate.
     */
    long countByEstimateIdAndDeletedFalse(@NonNull UUID estimateId);

    /**
     * Find all items for a given estimate with a specific approval status.
     * Used for promotion validation to verify approved scope exists.
     * 
     * @param estimateId     the ID of the estimate
     * @param approvalStatus the approval status to filter by
     * @return list of items matching the criteria
     */
    @NonNull
    List<EstimateItem> findByEstimateIdAndApprovalStatus(
            @NonNull UUID estimateId,
            @NonNull ApprovalStatus approvalStatus);

    /**
     * Find all non-deleted items for a given estimate with a specific approval
     * status.
     */
    @NonNull
    List<EstimateItem> findByEstimateIdAndApprovalStatusAndDeletedFalse(
            @NonNull UUID estimateId,
            @NonNull ApprovalStatus approvalStatus);
}
