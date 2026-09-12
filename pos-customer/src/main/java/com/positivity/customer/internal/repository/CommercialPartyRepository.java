package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.tenancy.TenantAudited;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for CommercialParty entities (CAP:091 Story #104).
 */
public interface CommercialPartyRepository
        extends JpaRepository<CommercialParty, UUID>, JpaSpecificationExecutor<CommercialParty> {
    CommercialParty findByPartyId(UUID partyId);

    @Query(
            "SELECT p FROM CommercialParty p WHERE LOWER(p.legalName) LIKE LOWER(CONCAT('%', :legalName, '%')) ORDER BY p.legalName")
    List<CommercialParty> findByLegalNameContaining(@Param("legalName") String legalName);

    /**
     * Commercial parties currently associated with the given vehicle VIN. Used by the
     * {@code vehicle.events.v1} consumer to keep the customer-owned vehicle-party association
     * aligned with the owner's accountId fact (ADR-0044 §6, ADR-0012).
     */
    @Query("SELECT p FROM CommercialParty p WHERE :vin MEMBER OF p.vehicleVins")
    List<CommercialParty> findByVehicleVin(@Param("vin") String vin);

    /**
     * Next value of the commercial customer-number sequence.
     *
     * <p>Customer numbers come from a sequence rather than from an id, because the id they used to
     * be derived from repeats: the first 8 hex characters of a UUIDv7 are the top 32 bits of its
     * millisecond timestamp, identical for every id minted within roughly 65 seconds.
     *
     * @return next sequence value for commercial customer number generation
     */
    @TenantAudited(
            reason =
                    "reads a sequence, not a table: customer numbers are unique platform-wide and carry no tenant data")
    @Query(value = "SELECT nextval('commercial_party_customer_number_seq')", nativeQuery = true)
    long getNextCustomerNumberSequence();

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
     * @param pageable the page to read, or {@code Pageable.unpaged()} for every match; its sort is
     *     replaced by {@link #BY_PARTY_ID} either way
     * @return the matching parties, in id order
     */
    @NonNull
    default List<CommercialParty> findForReplay(
            @Nullable UUID afterId, @Nullable Instant updatedSince, @NonNull Pageable pageable) {
        return findAll(
                        PartyReplaySearch.<CommercialParty>matching(afterId, updatedSince),
                        pageable.isUnpaged()
                                ? Pageable.unpaged(BY_PARTY_ID)
                                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), BY_PARTY_ID))
                .getContent();
    }
}
