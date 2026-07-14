package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtPickListReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtPickListReplicaRepository extends JpaRepository<ExtPickListReplica, UUID> {

    @NonNull
    List<ExtPickListReplica> findByWorkorderIdOrderByPickListIdAsc(@NonNull UUID workorderId);
}
