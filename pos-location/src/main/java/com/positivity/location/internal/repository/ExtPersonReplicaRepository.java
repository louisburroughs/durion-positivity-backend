package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.ExtPersonReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtPersonReplicaRepository extends JpaRepository<ExtPersonReplica, UUID> {}
