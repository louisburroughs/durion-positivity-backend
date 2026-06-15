package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.enums.PartyRelationshipRole;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
    List<PartyRelationship> findByFromPartyPartyId(@NonNull UUID partyId);

    /**
     * Find all active relationships for a commercial account.
     * Active means effective_end_date is null or in the future.
     *
     * @param partyId the party ID
     * @param today   the current date
     * @return list of active relationships
     */
    @Query("SELECT pr FROM PartyRelationship pr "
            + "JOIN FETCH pr.toPerson p "
            + "JOIN FETCH p.contactPoints "
            + "WHERE pr.fromParty.partyId = :partyId "
            + "AND (pr.effectiveEndDate IS NULL OR pr.effectiveEndDate >= :today)")
    List<PartyRelationship> findActiveByFromPartyId(
            @Param("partyId") @NonNull UUID partyId, @Param("today") @NonNull LocalDate today);

    /**
     * Find relationships for a person.
     *
     * @param personId the person ID
     * @return list of relationships
     */
    List<PartyRelationship> findByToPersonPartyId(@NonNull UUID personId);

    /**
     * Find all active relationships where this person is the contact.
     * Active means effective_end_date is null or in the future.
     *
     * @param personPartyId the person party ID
     * @param today         the current date
     * @return list of active relationships
     */
    @Query("SELECT pr FROM PartyRelationship pr "
            + "WHERE pr.toPerson.partyId = :personPartyId "
            + "AND (pr.effectiveEndDate IS NULL OR pr.effectiveEndDate >= :today)")
    List<PartyRelationship> findActiveByToPersonPartyId(
            @Param("personPartyId") @NonNull UUID personPartyId,
            @Param("today") @NonNull LocalDate today);

    /**
     * Find the primary billing contact relationship for a party.
     *
     * @param partyId the party ID
     * @param today   the current date
     * @return the primary billing contact relationship, if exists
     */
    @Query("SELECT pr FROM PartyRelationship pr WHERE pr.fromParty.partyId = :partyId "
            + "AND pr.isPrimaryBillingContact = true "
            + "AND (pr.effectiveEndDate IS NULL OR pr.effectiveEndDate >= :today)")
    Optional<PartyRelationship> findPrimaryBillingContact(
            @Param("partyId") @NonNull UUID partyId, @Param("today") @NonNull LocalDate today);

    /**
     * Find relationships with a specific role for a party.
     *
     * @param partyId the party ID
     * @param role    the role to filter by
     * @param today   the current date
     * @return list of matching relationships
     */
    @Query("SELECT pr FROM PartyRelationship pr JOIN pr.roles r WHERE pr.fromParty.partyId = :partyId "
            + "AND r = :role AND (pr.effectiveEndDate IS NULL OR pr.effectiveEndDate >= :today)")
    List<PartyRelationship> findByFromPartyIdAndRole(
            @Param("partyId") @NonNull UUID partyId,
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
    /**
     * Reassign all relationships from one party to another (used during merge).
     *
     * @param fromPartyId the losing party whose relationships will be transferred
     * @param toPartyId   the surviving party that will own the relationships
     * @return number of updated records
     */
    @Modifying
    @Query("UPDATE PartyRelationship pr SET pr.fromParty.partyId = :toPartyId "
            + "WHERE pr.fromParty.partyId = :fromPartyId")
    int reassignFromParty(@Param("fromPartyId") @NonNull UUID fromPartyId, @Param("toPartyId") @NonNull UUID toPartyId);

    @Modifying
    @Query("UPDATE PartyRelationship pr SET pr.isPrimaryBillingContact = false "
            + "WHERE pr.fromParty.partyId = :partyId AND pr.isPrimaryBillingContact = true "
            + "AND pr.partyRelationshipId != :excludeRelationId")
    int demoteExistingPrimaryBillingContact(
            @Param("partyId") @NonNull UUID partyId, @Param("excludeRelationId") @NonNull UUID excludeRelationId);

    /**
     * Check for overlapping active relationships for the same party pair and role.
     *
     * @param partyId  the party ID
     * @param personId the person ID
     * @param role     the role
     * @param today    the current date
     * @return list of overlapping relationships
     */
    @Query("SELECT pr FROM PartyRelationship pr JOIN pr.roles r WHERE pr.fromParty.partyId = :partyId "
            + "AND pr.toPerson.partyId = :personId AND r = :role "
            + "AND (pr.effectiveEndDate IS NULL OR pr.effectiveEndDate >= :today)")
    List<PartyRelationship> findOverlappingRelationships(
            @Param("partyId") @NonNull UUID partyId,
            @Param("personId") @NonNull UUID personId,
            @Param("role") @NonNull PartyRelationshipRole role,
            @Param("today") @NonNull LocalDate today);
}
