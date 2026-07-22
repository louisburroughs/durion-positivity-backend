package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ExtInvoiceLineReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only accessor for the {@code ext_invoice_line} replica (ADR-0044 §6, #924). */
public interface ExtInvoiceLineReplicaRepository extends JpaRepository<ExtInvoiceLineReplica, UUID> {

    List<ExtInvoiceLineReplica> findByInvoiceId(UUID invoiceId);

    /** Bulk line fetch for the bounded header page, collapsing the per-invoice N+1 (#1003 review, M1). */
    List<ExtInvoiceLineReplica> findByInvoiceIdIn(Collection<UUID> invoiceIds);

    void deleteByInvoiceId(UUID invoiceId);
}
