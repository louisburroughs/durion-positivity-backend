package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.MobileUnitEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for mobile units.
 *
 * Issue: #76
 */
@Repository
public interface MobileUnitRepository extends JpaRepository<MobileUnitEntity, UUID> {

    boolean existsByBaseLocationIdAndNameIgnoreCase(UUID baseLocationId, String name);
}
