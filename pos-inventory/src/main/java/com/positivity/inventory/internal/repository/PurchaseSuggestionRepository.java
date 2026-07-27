package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.PurchaseSuggestion;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseSuggestionRepository extends JpaRepository<PurchaseSuggestion, UUID> {

    /**
     * Open-suggestion guard/refresh lookup for one policy (odoo-parity F4, #1044): the
     * duplicate guard mirrors the replenishment-task guard — at most one
     * SUGGESTED/ACCEPTED suggestion per policy, refreshed instead of duplicated.
     */
    Optional<PurchaseSuggestion> findFirstByPolicyIdAndStatusInOrderByCreatedAtAsc(
            UUID policyId, List<PurchaseSuggestionStatus> statuses);

    /**
     * Suggestions joining the replenishment in-progress netting sum for one SKU/destination
     * (the F2 seam): callers pass SUGGESTED/ACCEPTED/CONVERTED and post-filter CONVERTED by
     * the linked PO's status — see
     * {@code PurchaseSuggestionCreationService#openSuggestionQuantity}.
     */
    List<PurchaseSuggestion> findByItemSKUAndLocationIdAndStatusIn(
            String itemSKU, UUID locationId, List<PurchaseSuggestionStatus> statuses);

    /**
     * Per-(policy, day) idempotency guard mirroring the batch-task guard: true when a
     * suggestion for the policy was already created on or after the day boundary,
     * regardless of its current status (a same-day dismissal does not re-suggest).
     */
    boolean existsByPolicyIdAndCreatedAtGreaterThanEqual(UUID policyId, Instant createdAtFrom);
}
