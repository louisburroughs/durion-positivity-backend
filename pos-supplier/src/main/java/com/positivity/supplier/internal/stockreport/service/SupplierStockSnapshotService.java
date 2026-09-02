package com.positivity.supplier.internal.stockreport.service;

import com.positivity.supplier.internal.service.model.PagedResponse;
import com.positivity.supplier.internal.stockreport.service.model.StockSnapshotLineView;
import com.positivity.supplier.internal.stockreport.service.model.StockSnapshotSummary;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read surface over fetched vendor stock-report snapshots (CAP-322; issue #1638 decision 5).
 *
 * <p>Reads only what the scheduled fetch already stored — no endpoint here contacts a vendor. The
 * live question "what does the vendor hold right now" is the stock inquiry (ADR-0044), a different
 * act with a different permission.
 *
 * <p>Snapshots are append-only and immutable, so the browse contract is two-step by design: first
 * resolve the profile's latest snapshot to its {@code snapshotId}, then page lines under that id.
 * All pages of one browse stay on one snapshot even if a newer report lands mid-browse.
 */
public interface SupplierStockSnapshotService {

    /**
     * Returns the metadata of a profile's latest snapshot, judged by the vendor-stated
     * {@code snapshotAsOf} (never by fetch time), with snapshots carrying no vendor-stated instant
     * ranked last.
     *
     * @param vendorProfileId the vendor profile to read
     * @return the latest snapshot's metadata, without lines
     * @throws com.positivity.supplier.internal.exception.SupplierNotFoundException when no snapshot
     *     exists for the profile — including when no such profile exists, since snapshots
     *     deliberately outlive profile configuration and are the only record consulted here
     */
    @NonNull
    StockSnapshotSummary getLatestSnapshot(@NonNull UUID vendorProfileId);

    /**
     * Returns one page of an immutable snapshot's lines, in document order.
     *
     * @param vendorProfileId the vendor profile the snapshot must belong to
     * @param snapshotId the snapshot to page
     * @param search case-insensitive contains-match against the article EAN, the vendor's article
     *     code and the description; blank is treated as absent
     * @param page zero-based page index
     * @param size page size
     * @return one page of lines
     * @throws com.positivity.supplier.internal.exception.SupplierNotFoundException when the
     *     snapshot does not exist or does not belong to the given profile
     */
    @NonNull
    PagedResponse<StockSnapshotLineView> listSnapshotLines(
            @NonNull UUID vendorProfileId, @NonNull UUID snapshotId, @Nullable String search, int page, int size);
}
