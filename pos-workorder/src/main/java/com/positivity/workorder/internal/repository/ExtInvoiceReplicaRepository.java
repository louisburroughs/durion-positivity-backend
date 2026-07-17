package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtInvoiceReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtInvoiceReplicaRepository extends JpaRepository<ExtInvoiceReplica, UUID> {}
