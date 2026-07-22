package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.TaxLiabilitySnapshot;
import com.positivity.accounting.internal.enums.TaxLiabilitySnapshotStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for frozen Sales-Tax Liability snapshots (issue #998). Snapshots are insert-only
 * except for the {@code ACTIVE -> SUPERSEDED} lifecycle transition; there are no delete or content
 * update paths.
 */
@Repository
public interface TaxLiabilitySnapshotRepository extends JpaRepository<TaxLiabilitySnapshot, UUID> {

    Optional<TaxLiabilitySnapshot> findByPeriodIdAndStatus(UUID periodId, TaxLiabilitySnapshotStatus status);

    List<TaxLiabilitySnapshot> findByPeriodCodeOrderByFrozenAtDesc(String periodCode);

    List<TaxLiabilitySnapshot> findAllByOrderByFrozenAtDesc();
}
