package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.LocationPriceOverrideEntity;
import com.positivity.catalog.internal.entity.PriceOverrideStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationPriceOverrideRepository extends JpaRepository<LocationPriceOverrideEntity, UUID> {

    Optional<LocationPriceOverrideEntity> findTopByLocationIdAndProductIdAndStatusOrderByCreatedAtDesc(
            UUID locationId,
            UUID productId,
            PriceOverrideStatus status);

    List<LocationPriceOverrideEntity> findByLocationIdAndProductIdAndStatus(
            UUID locationId,
            UUID productId,
            PriceOverrideStatus status);
}
