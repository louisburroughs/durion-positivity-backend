package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtEmployeeReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtEmployeeReplicaRepository extends JpaRepository<ExtEmployeeReplica, UUID> {

    /** Every employee row for one person; callers pick the current one with {@code ExtEmployeeReplica.latest}. */
    @NonNull
    List<ExtEmployeeReplica> findByPersonId(@NonNull UUID personId);

    @NonNull
    List<ExtEmployeeReplica> findByPersonIdIn(@NonNull Collection<UUID> personIds);
}
