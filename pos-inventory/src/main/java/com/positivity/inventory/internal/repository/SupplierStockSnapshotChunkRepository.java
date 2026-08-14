package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.SupplierStockSnapshotChunk;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Applied-chunk log for the supplier stock-report feed (CAP-322, #1312). */
public interface SupplierStockSnapshotChunkRepository extends JpaRepository<SupplierStockSnapshotChunk, UUID> {

    boolean existsBySnapshotIdAndChunkSequence(UUID snapshotId, int chunkSequence);

    /** The sequences that arrived, so a gap can be named and not merely counted. */
    List<SupplierStockSnapshotChunk> findBySnapshotIdOrderByChunkSequenceAsc(UUID snapshotId);
}
