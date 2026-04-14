package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.PersonParty;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for PersonParty entities (CAP:091 Story #104).
 */
@Repository
public interface PersonPartyRepository extends JpaRepository<PersonParty, UUID> {
    Optional<PersonParty> findByPersonId(@NonNull UUID personId);

    List<PersonParty> findByLastNameIgnoreCase(@NonNull String lastName);

    List<PersonParty> findByFirstNameIgnoreCaseAndLastNameIgnoreCase(
            @NonNull String firstName, @NonNull String lastName);

    @Query("SELECT p FROM PersonParty p WHERE " + "LOWER(p.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR "
            + "LOWER(p.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<PersonParty> searchByName(@Param("searchTerm") @NonNull String searchTerm);

    @Query("SELECT DISTINCT p FROM PersonParty p JOIN p.contactPoints cp WHERE "
            + "LOWER(cp.value) LIKE LOWER(CONCAT('%', :value, '%'))")
    List<PersonParty> findByContactPointValue(@Param("value") @NonNull String value);
}
