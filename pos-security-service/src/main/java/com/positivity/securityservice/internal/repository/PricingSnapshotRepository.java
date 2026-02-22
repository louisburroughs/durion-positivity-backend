package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.PricingSnapshot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for immutable pricing snapshots.
 *
 * Issue: #41
 */
@Repository
public interface PricingSnapshotRepository extends JpaRepository<PricingSnapshot, UUID> {
}
