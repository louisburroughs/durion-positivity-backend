package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtPersonCredentialReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtPersonCredentialReplicaRepository extends JpaRepository<ExtPersonCredentialReplica, UUID> {

    /** Every credential row for the given people, any status; the caller judges what is held on a date. */
    @NonNull
    List<ExtPersonCredentialReplica> findByPersonIdIn(@NonNull Collection<UUID> personIds);
}
