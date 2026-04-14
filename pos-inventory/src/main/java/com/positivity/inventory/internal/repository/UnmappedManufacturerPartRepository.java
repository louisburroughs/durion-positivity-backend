package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.UnmappedManufacturerPart;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for unmapped manufacturer part records.
 *
 * Issue: CAP-170 (#46)
 */
public interface UnmappedManufacturerPartRepository extends JpaRepository<UnmappedManufacturerPart, UUID> {

    Optional<UnmappedManufacturerPart> findByManufacturerIdAndManufacturerPartNumber(
            UUID manufacturerId, String manufacturerPartNumber);
}
