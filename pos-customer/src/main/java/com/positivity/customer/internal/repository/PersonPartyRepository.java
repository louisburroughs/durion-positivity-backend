package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.PersonParty;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Person parties currently associated with the given vehicle VIN. Used by the
     * {@code vehicle.events.v1} consumer to keep the customer-owned vehicle-party association
     * aligned with the owner's accountId fact (ADR-0044 §6, ADR-0012).
     */
    @Query("SELECT p FROM PersonParty p WHERE :vin MEMBER OF p.vehicleVins")
    List<PersonParty> findByVehicleVin(@Param("vin") @NonNull String vin);

    /**
     * A page of parties for fact replay (issue #1893), ordered by id so a cursor can resume where
     * the previous page stopped.
     *
     * <p>Cursor rather than offset paging on purpose: a replay of a large customer base runs over
     * several requests, and offsets shift under concurrent party writes — a party created
     * mid-replay would silently displace another out of the window and leave a replica short of
     * exactly the fact the replay was meant to deliver.
     */
    @Query("""
      SELECT p FROM PersonParty p
      WHERE (:afterId IS NULL OR p.partyId > :afterId)
        AND (:updatedSince IS NULL OR p.updatedAt >= :updatedSince)
      ORDER BY p.partyId ASC
      """)
    List<PersonParty> findForReplay(
            @Param("afterId") UUID afterId, @Param("updatedSince") Instant updatedSince, Pageable pageable);
}
