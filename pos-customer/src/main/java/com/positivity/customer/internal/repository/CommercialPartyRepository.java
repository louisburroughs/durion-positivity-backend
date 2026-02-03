package com.positivity.customer.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.customer.internal.entity.CommercialParty;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for CommercialParty entities (CAP:091 Story #104).
 */
@Repository
public interface CommercialPartyRepository extends JpaRepository<CommercialParty, UUID> {
    Optional<CommercialParty> findByPartyNumber(String partyNumber);

    CommercialParty findByPartyId(UUID partyId);
}
