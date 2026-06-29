package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.CommercialParty;
import java.util.List;
import java.util.UUID;
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
}
