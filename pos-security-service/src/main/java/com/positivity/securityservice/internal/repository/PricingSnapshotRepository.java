package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.PricingSnapshot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for immutable pricing snapshots.
 *
 * Issue: #41
 */
public interface PricingSnapshotRepository extends JpaRepository<PricingSnapshot, UUID> {}
