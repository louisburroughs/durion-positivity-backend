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

    boolean existsByConflict_Id(@NonNull UUID conflictId);
}
