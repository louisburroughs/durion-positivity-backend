package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtPersonReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtPersonReplicaRepository extends JpaRepository<ExtPersonReplica, UUID> {}
