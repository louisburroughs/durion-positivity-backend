package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ExtInvoiceLineReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only accessor for the {@code ext_invoice_line} replica (ADR-0044 §6, #924). */
public interface ExtInvoiceLineReplicaRepository extends JpaRepository<ExtInvoiceLineReplica, UUID> {

    List<ExtInvoiceLineReplica> findByInvoiceId(UUID invoiceId);

    void deleteByInvoiceId(UUID invoiceId);
}
