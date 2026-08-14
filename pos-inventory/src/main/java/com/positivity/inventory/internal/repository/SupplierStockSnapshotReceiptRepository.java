package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.SupplierStockSnapshotReceipt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Per-snapshot arrival state for the supplier stock-report feed (CAP-322, #1312). */
public interface SupplierStockSnapshotReceiptRepository extends JpaRepository<SupplierStockSnapshotReceipt, UUID> {

    Optional<SupplierStockSnapshotReceipt> findBySnapshotId(UUID snapshotId);
}
