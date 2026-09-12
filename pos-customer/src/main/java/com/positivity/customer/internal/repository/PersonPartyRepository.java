package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.PersonParty;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for PersonParty entities (CAP:091 Story #104).
 */
public interface PersonPartyRepository extends JpaRepository<PersonParty, UUID>, JpaSpecificationExecutor<PersonParty> {
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

    /** Replay order: by id, so a cursor can resume exactly where the previous page stopped. */
    Sort BY_PARTY_ID = Sort.by(Sort.Order.asc("partyId"));

    /**
     * A page of parties for fact replay (issue #1893), ordered by id so a cursor can resume where
     * the previous page stopped.
     *
     * <p>Cursor rather than offset paging on purpose: a replay of a large customer base runs over
     * several requests, and offsets shift under concurrent party writes — a party created
     * mid-replay would silently displace another out of the window and leave a replica short of
     * exactly the fact the replay was meant to deliver.
     *
     * <p>The filter is a {@link PartyReplaySearch} specification rather than a JPQL string of
     * {@code (:param IS NULL OR …)} clauses: see that class for why the string form returned 500
     * from PostgreSQL for every call while passing on H2.
     *
     * @param afterId resume cursor, or null to start at the beginning
     * @param updatedSince only parties changed at or after this instant, or null for every party
     * <p>Read through {@code findBy(...).limit(...).all()} rather than {@code findAll(spec,
     * pageable)}: this contract returns a list, the replay service never asks for a total, and a
     * {@code Page} return would have Spring Data run a count query per page — two potentially
     * full-table scans on every cursor request. The catalog replay repositories read the same way.
     *
     * @param pageable supplies the page size only — the replay is positioned by {@code afterId},
     *     not by an offset — or {@code Pageable.unpaged()} for every match; the order is always
     *     {@link #BY_PARTY_ID}
     * @return the matching parties, in id order
     */
    @NonNull
    default List<PersonParty> findForReplay(
            @Nullable UUID afterId, @Nullable Instant updatedSince, @NonNull Pageable pageable) {
        return findBy(PartyReplaySearch.<PersonParty>matching(afterId, updatedSince), query -> {
            var sorted = query.sortBy(BY_PARTY_ID);
            return (pageable.isUnpaged() ? sorted : sorted.limit(pageable.getPageSize())).all();
        });
    }
}
