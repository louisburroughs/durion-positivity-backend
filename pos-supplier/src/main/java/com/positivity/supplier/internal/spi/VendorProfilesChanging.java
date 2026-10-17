package com.positivity.supplier.internal.spi;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Published inside the transaction, just before a write that may move a vendor's purchasing channel (#2712): a profile
 * create, update, re-point, enable, disable, delete or YAML reconciliation. The vendor side snapshots each vendor's
 * designation so {@link VendorProfilesChanged} can tell a moved channel from an unchanged one. Lives in a package of
 * its own so the profile services (slice {@code service} / {@code config}) and the vendor slice share it without a
 * cycle.
 *
 * @param vendorIds the vendors whose profiles the write touches (the old and the new vendor of a re-point)
 */
public record VendorProfilesChanging(@NonNull Set<UUID> vendorIds) {}
