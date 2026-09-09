package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtLocationReplica;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only location replica carrying the location-scope ancestor sets (ADR-0061 §2, #1885). */
public interface ExtLocationReplicaRepository extends JpaRepository<ExtLocationReplica, UUID> {

    /**
     * Resolve the owner's short code — the value this module's GL {@code locationId} dimension
     * carries — back to the location id the ancestor sets are keyed on (#1885).
     *
     * @param code the owner's unique location code, e.g. {@code LOC-107}
     * @return the replicated location, or empty when the code is unknown to the replica
     */
    Optional<ExtLocationReplica> findByCode(String code);
}
