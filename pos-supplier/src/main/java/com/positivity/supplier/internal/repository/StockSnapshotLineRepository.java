package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.StockSnapshotLineEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Lines of a vendor stock snapshot (CAP-322). */
public interface StockSnapshotLineRepository extends JpaRepository<StockSnapshotLineEntity, UUID> {

    List<StockSnapshotLineEntity> findBySnapshotIdOrderByCreatedAtAsc(UUID snapshotId);

    long countBySnapshotId(UUID snapshotId);
}
