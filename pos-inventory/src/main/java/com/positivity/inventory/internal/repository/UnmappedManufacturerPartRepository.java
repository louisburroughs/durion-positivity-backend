package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.model.UnmappedManufacturerPart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for unmapped manufacturer part records.
 *
 * Issue: CAP-170 (#46)
 */
public interface UnmappedManufacturerPartRepository extends JpaRepository<UnmappedManufacturerPart, Long> {

    Optional<UnmappedManufacturerPart> findByManufacturerIdAndManufacturerPartNumber(String manufacturerId,
            String manufacturerPartNumber);
}
