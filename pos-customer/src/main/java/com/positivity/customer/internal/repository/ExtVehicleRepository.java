package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.ExtVehicle;
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
}
