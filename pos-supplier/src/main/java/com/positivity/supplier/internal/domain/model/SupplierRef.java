package com.positivity.supplier.internal.domain.model;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * Human-readable vendor profile alias (ADR-0050 §1), e.g. {@code "michelin-eu"}.
 *
 * <p>The platform identity of a vendor profile is {@code vendorProfileId} (UUIDv7); the alias
 * is an attribute for configuration, logs, and admin screens — never an identifier crossing a
 * contract boundary. Alias-to-UUID resolution arrives with the vendor profile persistence
 * slice (durion-positivity-backend#1222).
 *
 * @param value the unique profile alias; never blank
 */
public record SupplierRef(@NonNull String value) {

    public SupplierRef {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("SupplierRef value must not be blank");
        }
    }
}
