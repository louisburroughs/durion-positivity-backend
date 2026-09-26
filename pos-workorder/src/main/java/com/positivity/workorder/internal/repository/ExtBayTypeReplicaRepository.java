package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtBayTypeReplica;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Replicated per-bay-type specialty claims (DECISION-LOCATION-025, #2261), replace-set by the consumer. */
public interface ExtBayTypeReplicaRepository extends JpaRepository<ExtBayTypeReplica, UUID> {

    /**
     * Whether {@code bayType} takes general work by default. Empty means the tenant's bay specialty
     * map has not arrived yet — callers must not read that absence as {@code false}.
     */
    @NonNull
    Optional<ExtBayTypeReplica> findByBayType(@NonNull String bayType);

    /**
     * Any one row for the bound tenant, to read the {@code aggregateVersion} the tenant's whole map
     * was last applied at. Every row from one emission carries the same version; Hibernate's tenant
     * filter already scopes this query to the bound tenant, so the ordering is arbitrary
     * tie-breaking only, never load-bearing.
     */
    @NonNull
    Optional<ExtBayTypeReplica> findFirstByOrderByBayTypeAsc();
}
