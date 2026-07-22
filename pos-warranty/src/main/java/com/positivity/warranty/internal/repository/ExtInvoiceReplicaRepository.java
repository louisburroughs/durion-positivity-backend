package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ExtInvoiceReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only accessor for the {@code ext_invoice} replica (ADR-0044 §6, #924). */
public interface ExtInvoiceReplicaRepository extends JpaRepository<ExtInvoiceReplica, UUID> {

    List<ExtInvoiceReplica> findByPartyId(String partyId);

    /**
     * Newest-first, bounded header lookup for candidate-line search. Ordered by {@code invoice_id}
     * (a UUIDv7, so time-ordered and non-null) rather than {@code invoice_created_at} to keep the
     * ordering monotonic and free of NULL-ordering ambiguity across H2/Postgres. The {@link Pageable}
     * caps the fan-out so a long-lived party cannot trigger an unbounded scan (#1003 review, M1).
     */
    List<ExtInvoiceReplica> findByPartyIdOrderByInvoiceIdDesc(String partyId, Pageable pageable);
}
