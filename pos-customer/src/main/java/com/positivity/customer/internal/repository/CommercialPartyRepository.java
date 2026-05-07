package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.CommercialParty;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for CommercialParty entities (CAP:091 Story #104).
 */
@Repository
public interface CommercialPartyRepository extends JpaRepository<CommercialParty, UUID> {
    Optional<CommercialParty> findByPartyNumber(String partyNumber);

    CommercialParty findByPartyId(UUID partyId);

    @Query(
            "SELECT p FROM CommercialParty p WHERE LOWER(p.legalName) LIKE LOWER(CONCAT('%', :legalName, '%')) ORDER BY p.legalName")
    List<CommercialParty> findByLegalNameContaining(@Param("legalName") String legalName);
}
