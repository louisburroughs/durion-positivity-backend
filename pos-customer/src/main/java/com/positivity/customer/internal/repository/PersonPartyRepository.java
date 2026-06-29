package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.PersonParty;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for PersonParty entities (CAP:091 Story #104).
 */
public interface PersonPartyRepository extends JpaRepository<PersonParty, UUID> {
    Optional<PersonParty> findByPersonId(@NonNull UUID personId);

    /**
     * Find individual-customer persons: person parties that are NOT acting as a
     * commercial-account contact (i.e. not referenced by any party relationship).
     * These are the standalone individual customers shown in the customer directory.
     *
     * @return list of individual-customer person parties
     */
    @Query("SELECT p FROM PersonParty p WHERE p.partyId NOT IN "
            + "(SELECT pr.toPerson.partyId FROM PartyRelationship pr)")
    List<PersonParty> findIndividualCustomers();

    /**
     * Distinct canonical person ids linked from person parties. Used by person-link
     * reconciliation to verify every link resolves in pos-people (ADR-0015 I1).
     *
     * @return distinct non-null person ids
     */
    @Query("SELECT DISTINCT p.personId FROM PersonParty p WHERE p.personId IS NOT NULL")
    List<UUID> findDistinctPersonIds();
}
