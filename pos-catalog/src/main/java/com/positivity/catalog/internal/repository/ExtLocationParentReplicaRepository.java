package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ExtLocationParentReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only typed location-parent edges feeding the location-scope walks (ADR-0061 §2, #1885). */
public interface ExtLocationParentReplicaRepository
        extends JpaRepository<ExtLocationParentReplica, ExtLocationParentReplica.Key> {

    List<ExtLocationParentReplica> findByChildId(UUID childId);

    List<ExtLocationParentReplica> findByParentId(UUID parentId);

    void deleteByChildId(UUID childId);
}
