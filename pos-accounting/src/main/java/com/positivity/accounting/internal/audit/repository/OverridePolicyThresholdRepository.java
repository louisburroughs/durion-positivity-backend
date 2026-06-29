package com.positivity.accounting.internal.audit.repository;

import com.positivity.accounting.internal.audit.entity.OverridePolicyThreshold;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for override policy thresholds.
 */
public interface OverridePolicyThresholdRepository extends JpaRepository<OverridePolicyThreshold, UUID> {

    /**
     * Find the active policy for a role at a given point in time.
     */
    @Query("SELECT p FROM OverridePolicyThreshold p WHERE p.role = :role " + "AND p.active = true "
            + "AND p.effectiveDate <= :atTime "
            + "AND (p.expirationDate IS NULL OR p.expirationDate > :atTime) "
            + "ORDER BY p.effectiveDate DESC")
    Optional<OverridePolicyThreshold> findActiveByRoleAtTime(
            @Param("role") String role, @Param("atTime") Instant atTime);
}
