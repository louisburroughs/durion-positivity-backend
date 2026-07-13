package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.ExtCustomerPartyReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtCustomerPartyReplicaRepository extends JpaRepository<ExtCustomerPartyReplica, UUID> {

    /**
     * Case-insensitive display-name contains search for invoice free-text customer queries —
     * the replica equivalent of the CRM {@code GET /v1/crm/accounts/parties?name=} finder.
     */
    @Query("select r from ExtCustomerPartyReplica r"
            + " where lower(r.displayName) like lower(concat('%', :name, '%')) order by r.displayName")
    @NonNull
    List<ExtCustomerPartyReplica> searchByDisplayName(@Param("name") @NonNull String name, @NonNull Pageable pageable);
}
