package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ExtInvoiceReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only accessor for the {@code ext_invoice} replica (ADR-0044 §6, #924). */
public interface ExtInvoiceReplicaRepository extends JpaRepository<ExtInvoiceReplica, UUID> {

    List<ExtInvoiceReplica> findByPartyId(String partyId);
}
