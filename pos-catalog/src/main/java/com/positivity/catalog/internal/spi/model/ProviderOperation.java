package com.positivity.catalog.internal.spi.model;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One vendor operation applicable to a vehicle, in the vendor's own code space. Vendor codes
 * map onto Durion operation codes through {@code service_operation_xref}, never the reverse
 * (ADR-0059 §3).
 *
 * @param providerOperationCode the vendor's operation code
 * @param name the vendor's operation name
 * @param category the vendor's coarse category text, if it publishes one
 */
public record ProviderOperation(
        @NonNull String providerOperationCode,
        @NonNull String name,
        @Nullable String category) {}
