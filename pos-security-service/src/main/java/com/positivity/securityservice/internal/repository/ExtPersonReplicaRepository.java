package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.ExtPersonReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtPersonReplicaRepository extends JpaRepository<ExtPersonReplica, UUID> {

    @NonNull
    List<ExtPersonReplica> findByPrimaryEmailIgnoreCaseOrSecondaryEmailIgnoreCase(
            @NonNull String primaryEmail, @NonNull String secondaryEmail);

    @NonNull
    List<ExtPersonReplica> findByPrimaryPhoneOrSecondaryPhone(
            @NonNull String primaryPhone, @NonNull String secondaryPhone);

    @NonNull
    List<ExtPersonReplica> findByLastNameIgnoreCase(@NonNull String lastName);

    @NonNull
    List<ExtPersonReplica> findByFirstNameIgnoreCase(@NonNull String firstName);
}
