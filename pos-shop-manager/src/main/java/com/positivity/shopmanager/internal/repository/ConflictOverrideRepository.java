package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ConflictOverride;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.repository.Repository;

/**
 * Append-only by design (DECISION-SHOPMGMT-007): {@code save} and reads, no update path and no
 * delete. Extends the bare {@link Repository} rather than {@code JpaRepository} so the missing
 * methods are missing, not merely unused.
 */
public interface ConflictOverrideRepository extends Repository<ConflictOverride, UUID> {
    @NonNull
    ConflictOverride save(@NonNull ConflictOverride override);

    /**
     * {@link #save} plus an immediate flush, so that the {@code conflict_override_conflict_key}
     * unique constraint (V7) is hit inside the caller's try block rather than at commit, where a
     * second override racing the first would otherwise surface as a generic duplicate-resource 409.
     */
    @NonNull
    ConflictOverride saveAndFlush(@NonNull ConflictOverride override);

    boolean existsByConflict_Id(@NonNull UUID conflictId);
}
