package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.ExtVehicle;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtVehicleRepository extends JpaRepository<ExtVehicle, UUID> {

    @NonNull
    List<ExtVehicle> findByAccountIdAndActiveTrue(@NonNull UUID accountId);

    @NonNull
    Optional<ExtVehicle> findByVinNormalizedAndActiveTrue(@NonNull String vinNormalized);

    /**
     * Batch-load vehicles for a candidate party set — segment resolution tests vehicle
     * attributes for every candidate, so per-party lookups would be one query per row
     * (Story #1137).
     */
    @NonNull
    List<ExtVehicle> findByAccountIdIn(@NonNull Collection<UUID> accountIds);
}
