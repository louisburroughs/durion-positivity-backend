package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.ExtStorageLocationOnHandReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtStorageLocationOnHandReplicaRepository
        extends JpaRepository<ExtStorageLocationOnHandReplica, UUID> {}
