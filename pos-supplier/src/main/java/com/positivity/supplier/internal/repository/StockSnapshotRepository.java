package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.StockSnapshotEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only vendor stock snapshots (CAP-322). */
public interface StockSnapshotRepository extends JpaRepository<StockSnapshotEntity, UUID> {

    Page<StockSnapshotEntity> findByVendorProfileIdOrderByFetchedAtDesc(UUID vendorProfileId, Pageable pageable);

    Optional<StockSnapshotEntity> findFirstByVendorProfileIdAndStatusOrderByFetchedAtDesc(
            UUID vendorProfileId, String status);
}
