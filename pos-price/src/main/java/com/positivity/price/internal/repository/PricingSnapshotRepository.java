package com.positivity.price.internal.repository;

import com.positivity.price.internal.model.PricingSnapshot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for immutable pricing snapshots.
 *
 * Issue: #50
 */
public interface PricingSnapshotRepository extends JpaRepository<PricingSnapshot, UUID> {
}
