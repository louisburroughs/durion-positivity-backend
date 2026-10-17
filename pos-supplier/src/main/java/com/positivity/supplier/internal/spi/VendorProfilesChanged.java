package com.positivity.supplier.internal.spi;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Published inside the same transaction, right after the write {@link VendorProfilesChanging} announced (#2712). The
 * vendor side compares each vendor's designation with its snapshot and republishes the vendors whose channel moved.
 *
 * @param vendorIds the same vendors
 */
public record VendorProfilesChanged(@NonNull Set<UUID> vendorIds) {}
