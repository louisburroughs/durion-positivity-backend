package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.VendorResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * AP vendor directory: vendor name → vendorId resolution (Issue #816).
 *
 * <p>
 * Backs the frontend vendor-payment typeahead so operators search vendors by
 * name instead of typing raw UUIDs. The directory is populated from AP flows
 * that carry both vendorId and vendorName (goods-received events) via
 * {@link #recordVendor(UUID, String)}, plus a one-time backfill from existing
 * vendor bills and payments.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/816">Issue
 *      #816</a>
 */
public interface VendorDirectoryService {

    /**
     * Search vendors by name for typeahead use.
     *
     * <p>
     * Matching is case-insensitive contains on the vendor name; results are
     * ordered by name. A blank or null term lists all vendors (up to
     * {@code limit}).
     *
     * @param name  optional name search term (null/blank lists all vendors)
     * @param limit maximum results to return (clamped to a server-side cap)
     * @return name-matched vendors ordered by name
     */
    @NonNull
    List<VendorResponse> searchVendors(@Nullable String name, int limit);

    /**
     * Resolve a single vendor by id (e.g. to label a deep-linked
     * {@code ?vendorId=}).
     *
     * @param vendorId vendor UUID
     * @return the vendor, if present in the directory
     */
    @NonNull
    Optional<VendorResponse> getVendorById(@NonNull UUID vendorId);

    /**
     * Record (upsert) a vendor observed on an AP flow.
     *
     * <p>
     * Inserts a new directory entry, or refreshes the stored name when it has
     * changed. A null/blank name is ignored so call sites don't need to guard.
     * Runs in its own transaction; callers should treat it as best-effort and
     * not let a failure abort the surrounding business flow.
     *
     * @param vendorId   vendor UUID from the upstream event
     * @param vendorName vendor display name from the upstream event (may be
     *                   null/blank, in which case the call is a no-op)
     */
    void recordVendor(@NonNull UUID vendorId, @Nullable String vendorName);

    /**
     * {@link #recordVendor} for a caller that must stay on the connection it already holds (#2501):
     * the goods-receipt bill create, which holds its tenant's bill-number counter row lock until its
     * transaction ends, and on a small pool would wait for a second connection behind the writers
     * waiting for that lock. Runs in the caller's transaction, so the directory row commits or rolls
     * back with the bill. It never throws for the one failure {@code recordVendor} was isolated
     * against, a row that already exists (a concurrent insert, or another tenant's row under the
     * same id): the insert is conflict-tolerant. A null/blank name is ignored as before.
     *
     * @param vendorId   vendor UUID from the upstream event
     * @param vendorName vendor display name from the upstream event (may be null/blank, in which
     *                   case the call is a no-op)
     */
    void recordVendorInCurrentTransaction(@NonNull UUID vendorId, @Nullable String vendorName);
}
