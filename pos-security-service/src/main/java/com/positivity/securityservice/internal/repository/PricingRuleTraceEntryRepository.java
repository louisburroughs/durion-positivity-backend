package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.PricingRuleTraceEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for pricing rule trace entries linked to snapshots.
 *
 * Issue: #41
 */
public interface PricingRuleTraceEntryRepository extends JpaRepository<PricingRuleTraceEntry, UUID> {

    List<PricingRuleTraceEntry> findBySnapshot_SnapshotIdOrderByRuleIdAsc(UUID snapshotId);
}
