package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.ExtCatalogServiceReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtCatalogServiceReplicaRepository extends JpaRepository<ExtCatalogServiceReplica, UUID> {

    /**
     * Resolves specialty claims in one query rather than one per code.
     *
     * <p>Matches on {@code operationCode} because that is the vocabulary a bay declares (CAP-325
     * D14), and filters to {@code active} so a code whose service pos-catalog has retired stops
     * validating — the delete tombstone leaves the row in place with {@code active = false}
     * precisely so that a retired code is distinguishable from an unknown one.
     *
     * <p>Callers must uppercase and trim before calling: operation codes are {@code UPPER-DASH}
     * per ADR-0059 §3, and this comparison is exact.
     */
    @NonNull
    List<ExtCatalogServiceReplica> findByOperationCodeInAndActiveIsTrue(@NonNull Collection<String> operationCodes);
}
