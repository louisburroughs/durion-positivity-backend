package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtBaySpecialtyMapReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Replicated bay-type specialty operation codes (CAP-325 D14, #2261), replace-set by the consumer. */
public interface ExtBaySpecialtyMapReplicaRepository extends JpaRepository<ExtBaySpecialtyMapReplica, UUID> {

    /** The specialty codes a bay of this type is the only one able to perform. */
    @NonNull
    List<ExtBaySpecialtyMapReplica> findByBayType(@NonNull String bayType);

    /**
     * Whether any bay type claims {@code operationCode} as a specialty. {@code false} means the
     * operation is general work — or that the map has not arrived yet; the two read the same here
     * (see {@code ExtBaySpecialtyMapReplica}).
     */
    boolean existsByOperationCode(@NonNull String operationCode);
}
