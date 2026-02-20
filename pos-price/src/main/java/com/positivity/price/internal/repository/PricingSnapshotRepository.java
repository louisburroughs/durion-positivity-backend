package com.positivity.price.internal.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.price.internal.entity.PricingSnapshot;

/**
 * Repository for immutable pricing snapshots.
 *
 * Issue: #50
 */
public interface PricingSnapshotRepository extends JpaRepository<PricingSnapshot, UUID> {
}
