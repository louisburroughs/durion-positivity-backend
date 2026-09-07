package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtLocationParentReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ExtLocationParentReplicaRepository
        extends JpaRepository<ExtLocationParentReplica, ExtLocationParentReplica.Key> {

    /** Batched downward traversal: all edges of one type for a whole frontier level. */
    List<ExtLocationParentReplica> findByParentIdInAndParentType(Collection<UUID> parentIds, String parentType);

    /** Batched upward traversal (all edge types) for proximity BFS (odoo-parity H1, #1037). */
    List<ExtLocationParentReplica> findByChildIdIn(Collection<UUID> childIds);

    /** Batched downward traversal (all edge types) for proximity BFS (odoo-parity H1, #1037). */
    List<ExtLocationParentReplica> findByParentIdIn(Collection<UUID> parentIds);

    /** Upward step: the child's stored direct parent edges, every type (ADR-0061 §2, #1878). */
    List<ExtLocationParentReplica> findByChildId(UUID childId);

    /** Downward step: every stored edge naming this parent, every type (ADR-0061 §2, #1878). */
    List<ExtLocationParentReplica> findByParentId(UUID parentId);

    @Modifying
    void deleteByChildId(UUID childId);
}
