package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.StockSnapshotEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only vendor stock snapshots (CAP-322). */
public interface StockSnapshotRepository extends JpaRepository<StockSnapshotEntity, UUID> {

    Page<StockSnapshotEntity> findByVendorProfileIdOrderByFetchedAtDesc(UUID vendorProfileId, Pageable pageable);

    Optional<StockSnapshotEntity> findFirstByVendorProfileIdAndStatusOrderByFetchedAtDesc(
            UUID vendorProfileId, String status);

    /**
     * A profile's snapshots, newest <em>vendor-stated</em> moment first — pass a one-row page for
     * "the latest snapshot" (issue #1638 decision 5).
     *
     * <p>Ordered by {@code snapshotAsOf} rather than {@code fetchedAt} because latest-ness is a
     * claim about the vendor's stock position, and the vendor's own timestamp is the one that makes
     * it. Written as a query rather than derived for the {@code NULLS LAST}: a snapshot with no
     * vendor-stated instant (a failed or unparseable fetch) must never outrank one that has one —
     * and PostgreSQL sorts nulls <em>first</em> for {@code DESC}, which would let exactly that
     * happen. The UUIDv7 snapshot id tie-breaks rows sharing an instant, newest fetch first.
     */
    @Query("select s from StockSnapshotEntity s where s.vendorProfileId = :vendorProfileId"
            + " order by s.snapshotAsOf desc nulls last, s.snapshotId desc")
    @NonNull
    List<StockSnapshotEntity> findByVendorProfileIdNewestSnapshotAsOfFirst(
            @Param("vendorProfileId") @NonNull UUID vendorProfileId, @NonNull Pageable pageable);
}
