package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.MobileUnitEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for mobile units.
 *
 * Issue: #76
 */
public interface MobileUnitRepository extends JpaRepository<MobileUnitEntity, UUID> {

    @Query("""
            SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END
            FROM MobileUnitEntity m
            WHERE m.baseLocation.id = :baseLocationId
              AND UPPER(m.name) = UPPER(:name)
            """)
    boolean existsByBaseLocationIdAndNameIgnoreCase(UUID baseLocationId, String name);
}
