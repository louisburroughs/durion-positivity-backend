package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ExtWorkorderLineReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only accessor for the {@code ext_workorder_line} replica (ADR-0044 §6, #924). */
public interface ExtWorkorderLineReplicaRepository extends JpaRepository<ExtWorkorderLineReplica, UUID> {

    List<ExtWorkorderLineReplica> findByWorkorderId(UUID workorderId);

    void deleteByWorkorderId(UUID workorderId);
}
