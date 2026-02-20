package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.model.DistributorFeedException;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for distributor feed exception queue entries.
 *
 * Issue: CAP-170 (#47)
 */
public interface DistributorFeedExceptionRepository extends JpaRepository<DistributorFeedException, Long> {
}
