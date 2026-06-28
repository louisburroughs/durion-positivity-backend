package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.MergeAudit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for MergeAudit entity operations.
 */
public interface MergeAuditRepository extends JpaRepository<MergeAudit, UUID> {

    /**
     * Find all merge audits where the given party was the survivor.
     *
     * @param survivorPartyId the survivor party ID
     * @return list of merge audits
     */
    List<MergeAudit> findBySurvivorPartyId(@NonNull UUID survivorPartyId);

    /**
     * Find all merge audits where the given party was the source (merged).
     *
     * @param sourcePartyId the source party ID
     * @return list of merge audits
     */
    List<MergeAudit> findBySourcePartyId(@NonNull UUID sourcePartyId);

    /**
     * Find all merges performed by a specific user.
     *
     * @param userId the user ID
     * @return list of merge audits
     */
    List<MergeAudit> findByMergedByUserId(@NonNull UUID userId);
}
