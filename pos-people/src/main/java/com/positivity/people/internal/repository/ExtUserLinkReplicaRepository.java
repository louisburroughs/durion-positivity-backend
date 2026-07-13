package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.ExtUserLinkReplica;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtUserLinkReplicaRepository extends JpaRepository<ExtUserLinkReplica, UUID> {

    @NonNull
    List<ExtUserLinkReplica> findByPersonIdIn(@NonNull Collection<UUID> personIds);

    @NonNull
    Optional<ExtUserLinkReplica> findFirstByUsername(@NonNull String username);

    boolean existsByUsernameAndPersonId(@NonNull String username, @NonNull UUID personId);

    @NonNull
    List<ExtUserLinkReplica> findByPersonIdAndStatus(@NonNull UUID personId, @NonNull String status);

    @NonNull
    Optional<ExtUserLinkReplica> findFirstByPersonIdAndStatusOrderByLinkIdDesc(
            @NonNull UUID personId, @NonNull String status);
}
