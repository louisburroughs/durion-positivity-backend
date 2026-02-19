package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.dto.PartyRelationshipRole;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for PartyRelationship entity operations.
 */
@Repository
public interface PartyRelationshipRepository extends JpaRepository<PartyRelationship, UUID> {

        /**
         * Find all relationships for a commercial account (party).
         *
         * @param partyId the party ID
         * @return list of relationships
         */
        List<PartyRelationship> findByFromPartyId(@NonNull UUID partyId);

        /**
         * Find all active relationships for a commercial account.
         * Active means effective_end_date is null or in the future.
         *
         * @param partyId the party ID
         * @param today   the current date
         * @return list of active relationships
         */
        @Query("SELECT pr FROM PartyRelationship pr WHERE pr.fromParty.id = :partyId " +
                        "AND (pr.effectiveEndDate IS NULL OR pr.effectiveEndDate >= :today)")
        List<PartyRelationship> findActiveByFromPartyId(@Param("partyId") @NonNull UUID partyId,
                        @Param("today") @NonNull LocalDate today);

        /**
         * Find relationships for a person.
         *
         * @param personId the person ID
         * @return list of relationships
         */
        List<PartyRelationship> findByToPersonPersonId(@NonNull UUID personId);

        /**
         * Find the primary billing contact relationship for a party.
         *
         * @param partyId the party ID
         * @param today   the current date
         * @return the primary billing contact relationship, if exists
         */
        @Query("SELECT pr FROM PartyRelationship pr WHERE pr.fromParty.id = :partyId " +
                        "AND pr.isPrimaryBillingContact = true " +
                        "AND (pr.effectiveEndDate IS NULL OR pr.effectiveEndDate >= :today)")
        Optional<PartyRelationship> findPrimaryBillingContact(@Param("partyId") @NonNull UUID partyId,
                        @Param("today") @NonNull LocalDate today);

        /**
         * Find relationships with a specific role for a party.
         *
         * @param partyId the party ID
         * @param role    the role to filter by
         * @param today   the current date
         * @return list of matching relationships
         */
        @Query("SELECT pr FROM PartyRelationship pr JOIN pr.roles r WHERE pr.fromParty.id = :partyId " +
                        "AND r = :role AND (pr.effectiveEndDate IS NULL OR pr.effectiveEndDate >= :today)")
        List<PartyRelationship> findByFromPartyIdAndRole(@Param("partyId") @NonNull UUID partyId,
                        @Param("role") @NonNull PartyRelationshipRole role,
                        @Param("today") @NonNull LocalDate today);

        /**
         * Demote existing primary billing contact by setting isPrimaryBillingContact to
         * false.
         * Used when assigning a new primary billing contact.
         *
         * @param partyId           the party ID
         * @param excludeRelationId the relationship ID to exclude from demotion (the
         *                          new primary)
         * @return number of updated records
         */
        @Modifying
        @Query("UPDATE PartyRelationship pr SET pr.isPrimaryBillingContact = false " +
                        "WHERE pr.fromParty.id = :partyId AND pr.isPrimaryBillingContact = true " +
                        "AND pr.partyRelationshipId != :excludeRelationId")
        int demoteExistingPrimaryBillingContact(@Param("partyId") @NonNull UUID partyId,
                        @Param("excludeRelationId") @NonNull UUID excludeRelationId);

        /**
         * Check for overlapping active relationships for the same party pair and role.
         *
         * @param partyId  the party ID
         * @param personId the person ID
         * @param role     the role
         * @param today    the current date
         * @return list of overlapping relationships
         */
        @Query("SELECT pr FROM PartyRelationship pr JOIN pr.roles r WHERE pr.fromParty.id = :partyId " +
                        "AND pr.toPerson.personId = :personId AND r = :role " +
                        "AND (pr.effectiveEndDate IS NULL OR pr.effectiveEndDate >= :today)")
        List<PartyRelationship> findOverlappingRelationships(@Param("partyId") @NonNull UUID partyId,
                        @Param("personId") @NonNull UUID personId,
                        @Param("role") @NonNull PartyRelationshipRole role,
                        @Param("today") @NonNull LocalDate today);
}
