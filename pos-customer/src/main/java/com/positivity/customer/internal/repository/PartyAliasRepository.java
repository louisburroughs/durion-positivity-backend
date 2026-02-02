package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.PartyAlias;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for PartyAlias entity operations.
 * Used for ID redirection after merge operations.
 */
@Repository
public interface PartyAliasRepository extends JpaRepository<PartyAlias, Long> {

    /**
     * Find alias by source party ID.
     *
     * @param sourcePartyId the source party ID
     * @return the alias if exists
     */
    Optional<PartyAlias> findBySourcePartyId(@NonNull Long sourcePartyId);

    /**
     * Check if an alias exists for a source party ID.
     *
     * @param sourcePartyId the source party ID
     * @return true if alias exists
     */
    boolean existsBySourcePartyId(@NonNull Long sourcePartyId);

    /**
     * Find all aliases pointing to a target party.
     *
     * @param targetPartyId the target party ID
     * @return list of aliases
     */
    java.util.List<PartyAlias> findByTargetPartyId(@NonNull Long targetPartyId);
}
