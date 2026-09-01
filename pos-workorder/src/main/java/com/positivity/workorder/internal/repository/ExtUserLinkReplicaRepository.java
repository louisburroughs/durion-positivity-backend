package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtUserLinkReplica;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtUserLinkReplicaRepository extends JpaRepository<ExtUserLinkReplica, UUID> {

    @NonNull
    Optional<ExtUserLinkReplica> findFirstByUsernameAndStatus(@NonNull String username, @NonNull String status);

    /**
     * Batched form of {@link #findFirstByUsernameAndStatus} for resolving many distinct usernames
     * in one query (E5/E6, #1593/#1594) instead of one round trip per username.
     */
    @NonNull
    List<ExtUserLinkReplica> findByUsernameInAndStatus(@NonNull Collection<String> usernames, @NonNull String status);
}
