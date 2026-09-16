package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.PersonCredential;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonCredentialRepository extends JpaRepository<PersonCredential, UUID> {

    /** The natural key (spec D7): a renewal has a different {@code issuedOn} and is a new row. */
    Optional<PersonCredential> findByPersonIdAndSkill_IdAndIssuerAndIssuedOn(
            @NonNull UUID personId, @NonNull UUID skillId, @NonNull String issuer, @NonNull LocalDate issuedOn);

    @NonNull
    List<PersonCredential> findByPersonIdOrderByIssuedOnDesc(@NonNull UUID personId);

    @NonNull
    List<PersonCredential> findByPersonIdAndSourceSystem(@NonNull UUID personId, @NonNull String sourceSystem);
}
