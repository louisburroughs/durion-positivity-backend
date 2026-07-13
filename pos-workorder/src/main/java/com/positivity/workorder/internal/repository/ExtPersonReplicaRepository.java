package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtPersonReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtPersonReplicaRepository extends JpaRepository<ExtPersonReplica, UUID> {

    @NonNull
    List<ExtPersonReplica> findByPersonIdIn(@NonNull Collection<UUID> personIds);
}
