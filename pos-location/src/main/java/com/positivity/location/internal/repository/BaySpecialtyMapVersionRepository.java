package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.BaySpecialtyMapVersionEntity;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BaySpecialtyMapVersionRepository extends JpaRepository<BaySpecialtyMapVersionEntity, UUID> {

    /**
     * The bound tenant's single version row, if the map has ever been published for it (Hibernate's
     * tenant filter already scopes this to at most one row; the ordering is arbitrary tie-breaking
     * only, never load-bearing since {@code uq_bay_specialty_map_version_tenant} guarantees at most
     * one row per tenant).
     */
    @NonNull
    Optional<BaySpecialtyMapVersionEntity> findFirstByOrderByIdAsc();
}
