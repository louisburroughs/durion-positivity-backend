package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.ExtLocationReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtLocationReplicaRepository extends JpaRepository<ExtLocationReplica, UUID> {}
