package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.ExtLocationParentReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ExtLocationParentReplicaRepository
        extends JpaRepository<ExtLocationParentReplica, ExtLocationParentReplica.Key> {

    /** Upward step: the child's stored direct parent edges, every type (ADR-0061 §2, #1878). */
    List<ExtLocationParentReplica> findByChildId(UUID childId);

    /** Downward step: every stored edge naming this parent, every type (ADR-0061 §2, #1878). */
    List<ExtLocationParentReplica> findByParentId(UUID parentId);

    @Modifying
    void deleteByChildId(UUID childId);
}
