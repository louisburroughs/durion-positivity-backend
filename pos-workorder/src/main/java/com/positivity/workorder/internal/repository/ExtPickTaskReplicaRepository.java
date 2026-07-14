package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtPickTaskReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtPickTaskReplicaRepository extends JpaRepository<ExtPickTaskReplica, UUID> {

    @NonNull
    List<ExtPickTaskReplica> findByPickListIdOrderBySortOrderAsc(@NonNull UUID pickListId);
}
