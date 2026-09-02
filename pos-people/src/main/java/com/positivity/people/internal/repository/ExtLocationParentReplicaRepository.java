package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.ExtLocationParentReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ExtLocationParentReplicaRepository
        extends JpaRepository<ExtLocationParentReplica, ExtLocationParentReplica.Key> {

    @Modifying
    void deleteByChildId(UUID childId);
}
