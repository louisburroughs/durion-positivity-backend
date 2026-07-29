package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.CommunicationPreference;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * Batch-load preferences for a candidate set — segment resolution evaluates consent for
     * every candidate party, so a per-party lookup would be one query per row (Story #1137).
     *
     * @param partyIds the party UUIDs
     * @return preferences for the parties that have them
     */
    @NonNull
    List<CommunicationPreference> findByPartyIdIn(@NonNull Collection<UUID> partyIds);

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
