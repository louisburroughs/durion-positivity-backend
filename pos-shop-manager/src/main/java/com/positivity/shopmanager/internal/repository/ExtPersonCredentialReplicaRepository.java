package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtPersonCredentialReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Credential replica rows (CAP-328); written only by the people-events consumer. */
public interface ExtPersonCredentialReplicaRepository extends JpaRepository<ExtPersonCredentialReplica, UUID> {

    @NonNull
    List<ExtPersonCredentialReplica> findByPersonIdInOrderByIssuedOnDesc(@NonNull Collection<UUID> personIds);
}
