package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtInvoiceReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtInvoiceReplicaRepository extends JpaRepository<ExtInvoiceReplica, UUID> {

    /** Invoices attributable to a set of workorders (E5, #1593) — backs {@code laborRevenue}. */
    @NonNull
    List<ExtInvoiceReplica> findByWorkorderIdIn(@NonNull Collection<UUID> workorderIds);
}
