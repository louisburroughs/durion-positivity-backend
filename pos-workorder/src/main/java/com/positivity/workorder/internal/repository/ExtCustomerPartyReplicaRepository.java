package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtCustomerPartyReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtCustomerPartyReplicaRepository extends JpaRepository<ExtCustomerPartyReplica, UUID> {}
