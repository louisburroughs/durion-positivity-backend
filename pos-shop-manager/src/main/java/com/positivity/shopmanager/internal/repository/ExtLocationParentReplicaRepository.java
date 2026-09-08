package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtLocationParentReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ExtLocationParentReplicaRepository
        extends JpaRepository<ExtLocationParentReplica, ExtLocationParentReplica.Key> {

    /** Upward step: the child's stored direct parent edges, every type (ADR-0061 §2, #1872). */
    @NonNull
    List<ExtLocationParentReplica> findByChildId(@NonNull UUID childId);

    /** Downward step: every stored edge naming this parent, every type (ADR-0061 §2, #1872). */
    @NonNull
    List<ExtLocationParentReplica> findByParentId(@NonNull UUID parentId);

    @Modifying
    void deleteByChildId(@NonNull UUID childId);
}
