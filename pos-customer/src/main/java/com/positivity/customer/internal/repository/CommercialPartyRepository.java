package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.CommercialParty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for CommercialParty entities (CAP:091 Story #104).
 */
public interface CommercialPartyRepository extends JpaRepository<CommercialParty, UUID> {
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
    @Query(value = "SELECT nextval('commercial_party_customer_number_seq')", nativeQuery = true)
    long getNextCustomerNumberSequence();

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
      SELECT p FROM CommercialParty p
      WHERE (:afterId IS NULL OR p.partyId > :afterId)
        AND (:updatedSince IS NULL OR p.updatedAt >= :updatedSince)
      ORDER BY p.partyId ASC
      """)
    List<CommercialParty> findForReplay(
            @Param("afterId") UUID afterId, @Param("updatedSince") Instant updatedSince, Pageable pageable);
}
