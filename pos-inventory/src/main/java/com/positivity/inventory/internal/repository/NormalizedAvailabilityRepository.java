package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.model.NormalizedAvailability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for normalized manufacturer availability records.
 *
 * Issue: CAP-170 (#46)
 */
public interface NormalizedAvailabilityRepository extends JpaRepository<NormalizedAvailability, UUID> {

    Optional<NormalizedAvailability> findByProductIdAndManufacturerIdAndAsOf(UUID productId, String manufacturerId,
            Instant asOf);
}
