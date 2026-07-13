package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.ExtPersonReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtPersonReplicaRepository extends JpaRepository<ExtPersonReplica, UUID> {

    @NonNull
    List<ExtPersonReplica> findByPersonIdIn(@NonNull Collection<UUID> personIds);

    @NonNull
    List<ExtPersonReplica> findByLastNameIgnoreCase(@NonNull String lastName);

    @NonNull
    List<ExtPersonReplica> findByPrimaryEmailIgnoreCase(@NonNull String primaryEmail);

    @NonNull
    List<ExtPersonReplica> findByPrimaryPhoneOrSecondaryPhone(
            @NonNull String primaryPhone, @NonNull String secondaryPhone);
}
