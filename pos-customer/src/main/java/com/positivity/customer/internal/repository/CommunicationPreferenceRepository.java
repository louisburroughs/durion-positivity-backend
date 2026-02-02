package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.CommunicationPreference;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing communication preferences.
 * 
 * <p>
 * Provides queries for finding and managing communication preferences by party
 * ID.
 * </p>
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/107">Backend
 *      Issue #107</a>
 */
@Repository
public interface CommunicationPreferenceRepository extends JpaRepository<CommunicationPreference, UUID> {

    /**
     * Find communication preferences for a specific party.
     * 
     * @param partyId the party UUID
     * @return optional containing the preferences if they exist
     */
    @NonNull
    Optional<CommunicationPreference> findByPartyId(@NonNull UUID partyId);

    /**
     * Check if communication preferences exist for a party.
     * 
     * @param partyId the party UUID
     * @return true if preferences exist for the party
     */
    boolean existsByPartyId(@NonNull UUID partyId);

    /**
     * Delete communication preferences for a specific party.
     * 
     * @param partyId the party UUID
     */
    void deleteByPartyId(@NonNull UUID partyId);
}
