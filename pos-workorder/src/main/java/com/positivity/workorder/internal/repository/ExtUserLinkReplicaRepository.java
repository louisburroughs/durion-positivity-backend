package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtUserLinkReplica;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtUserLinkReplicaRepository extends JpaRepository<ExtUserLinkReplica, UUID> {

    @NonNull
    Optional<ExtUserLinkReplica> findFirstByUsernameAndStatus(@NonNull String username, @NonNull String status);
}
